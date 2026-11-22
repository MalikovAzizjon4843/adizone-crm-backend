package com.crm.service;

import com.crm.calls.CallContactMatcher;
import com.crm.calls.CallLookupService;
import com.crm.calls.CallRecordingService;
import com.crm.dto.response.LeadBulkDeleteResponse;
import com.crm.entity.AuditLog;
import com.crm.entity.User;
import com.crm.exception.CodedException;
import com.crm.exception.ConflictException;
import com.crm.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Lidni BUTUNLAY o'chirish (faqat SUPER_ADMIN): {@code DELETE /api/leads/{id}}, {@code POST /api/leads/bulk-delete}
 * va qo'ng'iroqlar ilovasidagi "noto'g'ri yuborilgan qo'ng'iroq" bilan ochilgan avto-lid.
 *
 * <p>Mantiq — {@code docs/ops/lead-hard-delete.sql} bilan AYNAN bir xil: o'sha himoyalar ({@link #check}) va o'sha
 * jadvallar, o'sha tartibda ({@link #deleteRows}, skriptdagi {@code hd_steps} raqamlari izohda). Yangi jadval
 * lid / vazifa / qo'ng'iroqqa bog'lansa — IKKALASINI ham yangilang ({@code LeadHardDeleteTest} pgtest'da
 * {@code pg_constraint} bo'yicha tekshiradi).
 *
 * <p>Bitta lid — bitta tranzaksiya ({@link #delete}); yozuv fayllari diskdan COMMIT dan keyin o'chadi. Audit:
 * {@code LEAD_DELETED} — faqat id, manba, kim, qachon (ism / telefon yozilmaydi).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LeadHardDeleteService {

    public static final String AUDIT_ACTION = "LEAD_DELETED";
    public static final int BULK_MAX = 200;

    /** Skript §1 dagi ruxsat ro'yxati: leads / tasks / calls ga FK bilan bog'langan, skript biladigan ustunlar. */
    public static final Set<String> KNOWN_FKS = Set.of(
        "lead_comments.lead_id", "lead_notes.lead_id", "lead_status_history.lead_id", "lead_assignments.lead_id",
        "tasks.lead_id", "students.converted_from_lead_id", "calls.lead_id", "call_requests.lead_id",
        "meta_webhook_events.lead_id", "call_recordings.call_id");

    /** O'chirish qanday boshlandi (audit {@code details_json.mode}). */
    public enum Mode { SINGLE, BULK, CALL_APP }

    public record Deleted(Long leadId, Map<String, Integer> rows, int files) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AuditLogRepository auditLogRepository;
    private final CallRecordingService recordingService;
    private final CallLookupService lookupService;
    private final Clock billingClock;

    // ── API ─────────────────────────────────────────────────────────────

    /**
     * Bitta lid. 404 {@code lead.delete.notFound}; 409 {@code lead.delete.converted} / {@code lead.delete.hasPayment}
     * / {@code lead.delete.blocked} (skript bilmaydigan bog'lanish — hech narsa o'chmaydi).
     */
    public Deleted delete(Long leadId, User actor, Mode mode) {
        Steps steps;
        try {
            steps = tx.execute(s -> deleteInTx(leadId, actor, mode));
        } catch (DataIntegrityViolationException e) {
            log.warn("Lid #{} o'chirilmadi — bog'langan ma'lumot: {}", leadId, e.getMostSpecificCause().getMessage());
            throw new ConflictException("lead.delete.blocked");
        }
        int files = recordingService.deleteFiles(steps.files());
        if (steps.phone() != null) {
            lookupService.evict(CallContactMatcher.normalize(steps.phone()));
        }
        log.info("Lid #{} butunlay o'chirildi ({}, user #{}): {}; fayllar: {}", leadId, mode,
            actor != null ? actor.getId() : null, steps.rows(), files);
        return new Deleted(leadId, steps.rows(), files);
    }

    /** Ommaviy: har lid alohida tranzaksiyada; o'chmaganlari — sabab (xato kodi) bilan. */
    public LeadBulkDeleteResponse bulkDelete(List<Long> leadIds, User actor) {
        if (leadIds == null || leadIds.isEmpty()) {
            throw CodedException.badRequest("lead.bulkDelete.empty");
        }
        Set<Long> ids = leadIds.stream().filter(java.util.Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            throw CodedException.badRequest("lead.bulkDelete.empty");
        }
        if (ids.size() > BULK_MAX) {
            throw CodedException.badRequest("lead.bulkDelete.tooMany", BULK_MAX);
        }
        List<Long> deleted = new ArrayList<>();
        List<LeadBulkDeleteResponse.Skipped> skipped = new ArrayList<>();
        for (Long id : ids) {
            try {
                delete(id, actor, Mode.BULK);
                deleted.add(id);
            } catch (CodedException e) {
                skipped.add(new LeadBulkDeleteResponse.Skipped(id, e.getCode()));
            }
        }
        return new LeadBulkDeleteResponse(ids.size(), deleted.size(), deleted, skipped);
    }

    // ── Tranzaksiya ichida ─────────────────────────────────────────────

    private record Steps(Map<String, Integer> rows, List<String> files, String phone) {
    }

    private record LeadRow(Long id, String fullName, String phone, String source, LocalDateTime createdAt) {
    }

    private Steps deleteInTx(Long leadId, User actor, Mode mode) {
        MapSqlParameterSource id = new MapSqlParameterSource("id", leadId);
        // Qulf: tekshiruv va o'chirish orasida lid o'zgarmasin (skript: SELECT ... FOR UPDATE)
        List<Long> locked = jdbc.queryForList("SELECT id FROM leads WHERE id = :id FOR UPDATE", id, Long.class);
        if (locked.isEmpty()) {
            throw CodedException.notFound("lead.delete.notFound");
        }
        check(leadId);
        LeadRow lead = jdbc.queryForObject(
            "SELECT id, full_name, phone, source, created_at FROM leads WHERE id = :id", id,
            (rs, i) -> new LeadRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getTimestamp(5) != null ? rs.getTimestamp(5).toLocalDateTime() : null));

        List<Long> taskIds = jdbc.queryForList("SELECT id FROM tasks WHERE lead_id = :id ORDER BY id", id, Long.class);
        List<String> files = jdbc.queryForList("""
                SELECT r.path FROM call_recordings r JOIN calls c ON c.id = r.call_id
                WHERE c.lead_id = :id AND r.path IS NOT NULL ORDER BY r.call_id
                """, id, String.class);
        Map<String, Integer> rows = deleteRows(lead, taskIds);

        Integer left = jdbc.queryForObject("SELECT COUNT(*) FROM leads WHERE id = :id", id, Integer.class);
        if (left == null || left != 0) {
            throw new IllegalStateException("Lid #" + leadId + " o'chmadi");
        }
        audit(lead, actor, mode, taskIds.size(), rows);
        return new Steps(rows, files, lead.phone());
    }

    /** Skript {@code hd_check}: o'quvchiga aylantirilgan yoki to'lovi bor lid o'chirilmaydi (409). */
    private void check(Long leadId) {
        MapSqlParameterSource id = new MapSqlParameterSource("id", leadId);
        Integer converted = jdbc.queryForObject("""
                SELECT COUNT(*) FROM leads l
                WHERE l.id = :id
                  AND (COALESCE(l.converted, FALSE)
                       OR l.converted_at IS NOT NULL
                       OR l.student_id IS NOT NULL
                       OR EXISTS (SELECT 1 FROM students s WHERE s.converted_from_lead_id = l.id)
                       OR EXISTS (SELECT 1 FROM lead_stages st WHERE st.code = l.status AND upper(st.kind) = 'CONVERTED')
                       OR EXISTS (SELECT 1 FROM lead_status_history h JOIN lead_stages st ON st.code = h.to_status
                                  WHERE h.lead_id = l.id AND upper(st.kind) = 'CONVERTED'))
                """, id, Integer.class);
        if (converted != null && converted > 0) {
            throw new ConflictException("lead.delete.converted");
        }
        Integer paid = jdbc.queryForObject("""
                SELECT COUNT(*) FROM leads l
                WHERE l.id = :id
                  AND (l.amount IS NOT NULL
                       OR EXISTS (SELECT 1 FROM lead_stages st WHERE st.code = l.status AND st.requires_amount = TRUE)
                       OR EXISTS (SELECT 1 FROM lead_status_history h JOIN lead_stages st ON st.code = h.to_status
                                  WHERE h.lead_id = l.id AND st.requires_amount = TRUE)
                       OR EXISTS (SELECT 1 FROM lead_comments c WHERE c.lead_id = l.id
                                  AND c.text LIKE 'To''lov qabul qilindi%'))
                """, id, Integer.class);
        if (paid != null && paid > 0) {
            throw new ConflictException("lead.delete.hasPayment");
        }
    }

    /** Skript {@code hd_steps} — o'sha jadvallar, o'sha tartib. Qaytaradi: "tartib jadval" → o'chirilgan qatorlar. */
    private Map<String, Integer> deleteRows(LeadRow lead, List<Long> taskIds) {
        Long leadId = lead.id();
        List<Long> tids = taskIds.isEmpty() ? List.of(-1L) : taskIds;
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("id", leadId).addValue("tids", tids);
        Map<String, Integer> rows = new LinkedHashMap<>();

        // 10 user_notifications — entity / link / dedupe (FK yo'q)
        List<Long> notifications = notificationIds(leadId, taskIds);
        rows.put("10 user_notifications", notifications.isEmpty() ? 0
            : jdbc.update("DELETE FROM user_notifications WHERE id IN (:n)", new MapSqlParameterSource("n", notifications)));

        // 20 telegram_outbox — vazifa eslatmalari (dedupe task:ID:)
        int outboxTasks = 0;
        for (Long tid : taskIds) {
            outboxTasks += jdbc.update("DELETE FROM telegram_outbox WHERE dedupe_key LIKE :k",
                new MapSqlParameterSource("k", "task:" + tid + ":%"));
        }
        rows.put("20 telegram_outbox", outboxTasks);

        // 21 telegram_outbox — LEAD_ASSIGNED, matnida lid ismi (≥ 5 belgi), lid yaratilgandan keyin
        int outboxAssigned = 0;
        String name = lead.fullName();
        // created_at NULL bo'lsa skriptda ham shart NULL → hech narsa
        if (name != null && name.length() >= 5 && lead.createdAt() != null) {
            MapSqlParameterSource q = new MapSqlParameterSource()
                .addValue("pat", "%" + escapeLike(name) + "%")
                .addValue("since", Timestamp.valueOf(lead.createdAt()));
            outboxAssigned = jdbc.update("""
                    DELETE FROM telegram_outbox
                    WHERE event_code = 'LEAD_ASSIGNED' AND text LIKE :pat ESCAPE '\\'
                      AND created_at >= :since
                    """, q);
        }
        rows.put("21 telegram_outbox", outboxAssigned);

        // 30 audit_logs — Lead va uning vazifalari
        rows.put("30 audit_logs", jdbc.update("""
                DELETE FROM audit_logs
                WHERE (entity_type = 'Lead' AND entity_id = :id) OR (entity_type = 'Task' AND entity_id IN (:tids))
                """, p));

        // 40–42 qo'ng'iroqlar (V90)
        rows.put("40 call_requests", jdbc.update("DELETE FROM call_requests WHERE lead_id = :id", p));
        rows.put("41 call_recordings", jdbc.update(
            "DELETE FROM call_recordings WHERE call_id IN (SELECT c.id FROM calls c WHERE c.lead_id = :id)", p));
        rows.put("42 calls", jdbc.update("DELETE FROM calls WHERE lead_id = :id", p));

        // 50 meta_webhook_events (FK yo'q; Meta raw JSON da shaxsiy ma'lumot)
        rows.put("50 meta_webhook_events", jdbc.update("DELETE FROM meta_webhook_events WHERE lead_id = :id", p));

        // 60–63 lid tarixi
        rows.put("60 lead_assignments", jdbc.update("DELETE FROM lead_assignments WHERE lead_id = :id", p));
        rows.put("61 lead_comments", jdbc.update("DELETE FROM lead_comments WHERE lead_id = :id", p));
        rows.put("62 lead_notes", jdbc.update("DELETE FROM lead_notes WHERE lead_id = :id", p));
        rows.put("63 lead_status_history", jdbc.update("DELETE FROM lead_status_history WHERE lead_id = :id", p));

        // 70 tasks, 90 leads
        rows.put("70 tasks", jdbc.update("DELETE FROM tasks WHERE lead_id = :id", p));
        rows.put("90 leads", jdbc.update("DELETE FROM leads WHERE id = :id", p));
        return rows;
    }

    /**
     * Skript §2 qadam 10 sharti: {@code entity_type} Lead/Task + id, {@code link ~ '^/leads\?(.*&)?lead=ID([^0-9]|$)'}
     * (va {@code /tasks?task=}), {@code dedupe_key ~ '^task:ID:'}. Nomzodlar LIKE bilan olinadi, aniq shart — shu
     * regex'lar (H2 va PostgreSQL da bir xil natija).
     */
    private List<Long> notificationIds(Long leadId, List<Long> taskIds) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, entity_type, entity_id, link, dedupe_key FROM user_notifications
                WHERE (lower(entity_type) = 'lead' AND entity_id = :id)
                   OR (lower(entity_type) = 'task' AND entity_id IN (:tids))
                   OR link LIKE :leadPat
                """);
        MapSqlParameterSource p = new MapSqlParameterSource()
            .addValue("id", leadId)
            .addValue("tids", taskIds.isEmpty() ? List.of(-1L) : taskIds)
            .addValue("leadPat", "/leads?%lead=" + leadId + "%");
        for (int i = 0; i < taskIds.size(); i++) {
            sql.append(" OR link LIKE :tl").append(i).append(" OR dedupe_key LIKE :tk").append(i);
            p.addValue("tl" + i, "/tasks?%task=" + taskIds.get(i) + "%");
            p.addValue("tk" + i, "task:" + taskIds.get(i) + ":%");
        }
        Pattern leadRe = Pattern.compile("^/leads\\?(.*&)?lead=(" + leadId + ")([^0-9]|$)");
        String tidAlt = taskIds.stream().map(String::valueOf).collect(Collectors.joining("|"));
        Pattern taskRe = taskIds.isEmpty() ? null : Pattern.compile("^/tasks\\?(.*&)?task=(" + tidAlt + ")([^0-9]|$)");
        Pattern taskKey = taskIds.isEmpty() ? null : Pattern.compile("^task:(" + tidAlt + "):");
        Set<Long> tidSet = Set.copyOf(taskIds);
        return jdbc.query(sql.toString(), p, (rs, i) -> {
            String type = rs.getString(2) == null ? null : rs.getString(2).toLowerCase(Locale.ROOT);
            long entityId = rs.getLong(3);
            boolean hasEntity = !rs.wasNull();
            String link = rs.getString(4);
            String key = rs.getString(5);
            boolean match = (hasEntity && "lead".equals(type) && entityId == leadId)
                || (hasEntity && "task".equals(type) && tidSet.contains(entityId))
                || (link != null && leadRe.matcher(link).find())
                || (link != null && taskRe != null && taskRe.matcher(link).find())
                || (key != null && taskKey != null && taskKey.matcher(key).find());
            return match ? rs.getLong(1) : null;
        }).stream().filter(java.util.Objects::nonNull).toList();
    }

    /** {@code LEAD_DELETED}: faqat id, manba, kim, qachon — ism / telefon YO'Q. Shu tranzaksiyada (atomar). */
    private void audit(LeadRow lead, User actor, Mode mode, int tasks, Map<String, Integer> rows) {
        String details = "{\"leadId\":" + lead.id()
            + ",\"source\":" + json(lead.source())
            + ",\"mode\":\"" + mode.name() + "\""
            + ",\"tasks\":" + tasks
            + ",\"calls\":" + rows.getOrDefault("42 calls", 0) + "}";
        auditLogRepository.save(AuditLog.builder()
            .createdAt(LocalDateTime.now(billingClock))
            .userId(actor != null ? actor.getId() : null)
            .username(actor != null ? actor.getUsername() : null)
            .userRole(actor != null && actor.getRole() != null ? actor.getRole().name() : null)
            .action(AUDIT_ACTION)
            .entityType("Lead")
            .entityId(lead.id())
            .summary("Lid #" + lead.id() + " butunlay o'chirildi" + (lead.source() != null ? " (manba: "
                + cut(lead.source(), 60) + ")" : ""))
            .detailsJson(details)
            .build());
    }

    private static String json(String s) {
        if (s == null) {
            return "null";
        }
        return "\"" + cut(s, 60).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
