package com.crm.calls;

import com.crm.entity.Lead;
import com.crm.entity.LeadAssignment;
import com.crm.entity.LeadComment;
import com.crm.entity.LeadNote;
import com.crm.entity.LeadStatusHistory;
import com.crm.entity.MetaWebhookEvent;
import com.crm.entity.Task;
import com.crm.entity.TelegramOutbox;
import com.crm.entity.User;
import com.crm.entity.UserNotification;
import com.crm.entity.enums.TaskStatus;
import com.crm.entity.enums.UserRole;
import com.crm.service.LeadHardDeleteService;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lidni butunlay o'chirish ({@code DELETE /api/leads/{id}}, {@code POST /api/leads/bulk-delete}) —
 * {@code docs/ops/lead-hard-delete.sql} bilan bir xil jadvallar, himoyalar va audit. Qo'ng'iroq / yozuv fixturelari
 * uchun {@link CallsItBase} ishlatiladi.
 */
class LeadHardDeleteTest extends CallsItBase {

    @Autowired private EntityManager em;

    private record Seed(Long leadId, Long taskId, Long callId, String recording, String name) {
    }

    /** Lid va unga bog'liq hamma narsa (skriptdagi har bir qadam uchun kamida bitta qator). */
    private Seed seedFull(User owner, String name, String phone) throws Exception {
        Long leadId = lead(name, phone, owner);
        Long taskId = inTx(() -> {
            Lead lead = em.find(Lead.class, leadId);
            User u = em.find(User.class, owner.getId());
            Task t = Task.builder().title("Qo'ng'iroq qilish").dueAt(LocalDateTime.now().plusDays(1))
                .status(TaskStatus.OPEN).assignedTo(u).lead(lead).autoCreated(false).build();
            em.persist(t);
            em.persist(LeadComment.builder().lead(lead).author(u).text("Izoh").createdAt(LocalDateTime.now()).build());
            em.persist(LeadNote.builder().lead(lead).text("Eslatma").createdBy(u).build());
            em.persist(LeadStatusHistory.builder().lead(lead).fromStatus(null).toStatus("NEW").changedBy(u)
                .changedAt(LocalDateTime.now()).build());
            em.persist(LeadAssignment.builder().leadId(leadId).userId(u.getId()).assignedAt(LocalDateTime.now())
                .build());
            em.persist(MetaWebhookEvent.builder().leadgenId("lg-" + leadId + "-" + System.nanoTime())
                .leadId(leadId).receivedAt(Instant.now()).build());
            return t.getId();
        });
        Long tid = taskId;
        inTx(() -> {
            em.persist(notification(owner, "Lead", leadId, null, null));
            em.persist(notification(owner, null, null, "/leads?lead=" + leadId + "&tab=calls", null));
            em.persist(notification(owner, "Task", tid, null, null));
            em.persist(notification(owner, null, null, "/tasks?task=" + tid, null));
            em.persist(notification(owner, null, null, null, "task:" + tid + ":overdue"));
            em.persist(outbox("Eslatma", "task:" + tid + ":remind", null));
            em.persist(outbox("Sizga yangi lid: " + name, null, "LEAD_ASSIGNED"));
            return null;
        });
        jdbc.update("INSERT INTO audit_logs (created_at, action, entity_type, entity_id) VALUES (?, 'UPDATE', 'Lead', ?)",
            LocalDateTime.now(), leadId);
        jdbc.update("INSERT INTO audit_logs (created_at, action, entity_type, entity_id) VALUES (?, 'CREATE', 'Task', ?)",
            LocalDateTime.now(), tid);

        String token = register(owner);
        Long callId = callId(syncOk(token, item("hd-" + leadId, "OUT", phone, 30, 60)), 0);
        assertThat(callRepository.findById(callId).orElseThrow().getLeadId()).isEqualTo(leadId);
        String rec = uploadRecording(token, callId, ("AUDIO-" + leadId).getBytes(StandardCharsets.UTF_8));
        jdbc.update("""
                INSERT INTO call_requests (user_id, phone, lead_id, status, created_at, expires_at, closed_at)
                VALUES (?, ?, ?, 'CANCELLED', ?, ?, ?)
                """, owner.getId(), phone, leadId, LocalDateTime.now(), LocalDateTime.now().plusMinutes(2),
            LocalDateTime.now());
        return new Seed(leadId, tid, callId, rec, name);
    }

    private static UserNotification notification(User u, String entityType, Long entityId, String link, String key) {
        return UserNotification.builder().userId(u.getId()).type("TEST").title("Bildirishnoma")
            .entityType(entityType).entityId(entityId).link(link).dedupeKey(key).createdAt(LocalDateTime.now()).build();
    }

    private static TelegramOutbox outbox(String text, String key, String event) {
        return TelegramOutbox.builder().chatId(1L).text(text).silent(false).priority(TelegramOutbox.Priority.NORMAL)
            .status(TelegramOutbox.Status.PENDING).attempts(0).notBefore(LocalDateTime.now()).dedupeKey(key)
            .eventCode(event).createdAt(LocalDateTime.now()).build();
    }

    private Map<String, Long> footprint(Seed s) {
        return Map.ofEntries(
            Map.entry("leads", n("SELECT COUNT(*) FROM leads WHERE id = ?", s.leadId())),
            Map.entry("tasks", n("SELECT COUNT(*) FROM tasks WHERE lead_id = ?", s.leadId())),
            Map.entry("lead_comments", n("SELECT COUNT(*) FROM lead_comments WHERE lead_id = ?", s.leadId())),
            Map.entry("lead_notes", n("SELECT COUNT(*) FROM lead_notes WHERE lead_id = ?", s.leadId())),
            Map.entry("lead_status_history", n("SELECT COUNT(*) FROM lead_status_history WHERE lead_id = ?", s.leadId())),
            Map.entry("lead_assignments", n("SELECT COUNT(*) FROM lead_assignments WHERE lead_id = ?", s.leadId())),
            Map.entry("meta_webhook_events", n("SELECT COUNT(*) FROM meta_webhook_events WHERE lead_id = ?", s.leadId())),
            Map.entry("calls", n("SELECT COUNT(*) FROM calls WHERE lead_id = ?", s.leadId())),
            Map.entry("call_recordings", n("SELECT COUNT(*) FROM call_recordings WHERE call_id = ?", s.callId())),
            Map.entry("call_requests", n("SELECT COUNT(*) FROM call_requests WHERE lead_id = ?", s.leadId())),
            Map.entry("audit_logs", n("SELECT COUNT(*) FROM audit_logs WHERE (entity_type = 'Lead' AND entity_id = ? "
                + "AND action <> 'LEAD_DELETED') OR (entity_type = 'Task' AND entity_id = ?)", s.leadId(), s.taskId())),
            Map.entry("user_notifications", n("SELECT COUNT(*) FROM user_notifications WHERE entity_id IN (?, ?) "
                + "OR link LIKE ? OR link = ? OR dedupe_key LIKE ?", s.leadId(), s.taskId(),
                "/leads?lead=" + s.leadId() + "&%", "/tasks?task=" + s.taskId(), "task:" + s.taskId() + ":%")),
            Map.entry("telegram_outbox", n("SELECT COUNT(*) FROM telegram_outbox WHERE dedupe_key LIKE ? "
                + "OR (event_code = 'LEAD_ASSIGNED' AND text LIKE ?)", "task:" + s.taskId() + ":%",
                "%" + s.name() + "%")));
    }

    private long n(String sql, Object... args) {
        Long v = jdbc.queryForObject(sql, Long.class, args);
        return v == null ? 0 : v;
    }

    // ── Bitta lid ────────────────────────────────────────────────────────

    @Test
    void delete_superAdminOnly_removesEverySqlScriptStep_fileAfterCommit_andAudit() throws Exception {
        User sa = staff(UserRole.SUPER_ADMIN);
        User sm = staff(UserRole.SALES_MANAGER);
        String targetPhone = phone();
        Seed target = seedFull(sm, "Alisher Valiyev", targetPhone);
        Seed control = seedFull(staff(UserRole.SALES_MANAGER), "Boshqa Mijoz", phone());
        Map<String, Long> controlBefore = footprint(control);
        assertThat(footprint(target).values()).allMatch(v -> v >= 1);
        assertThat(Files.exists(recordingFile(target.recording()))).isTrue();
        // Raqami prefiks bo'lgan boshqa lid havolasi — o'chmasin (skript regex: id dan keyin raqam emas)
        Long similar = inTx(() -> {
            UserNotification x = notification(sm, null, null, "/leads?lead=" + target.leadId() + "0", null);
            em.persist(x);
            return x.getId();
        });

        for (UserRole role : List.of(UserRole.SALES_MANAGER, UserRole.ADMIN, UserRole.SALES_HEAD)) {
            mvc.perform(delete("/api/leads/" + target.leadId()).with(as(staff(role)))).andExpect(status().isForbidden());
        }
        assertThat(n("SELECT COUNT(*) FROM leads WHERE id = ?", target.leadId())).isOne();

        mvc.perform(delete("/api/leads/" + target.leadId()).with(as(sa))).andExpect(status().isOk());

        assertThat(footprint(target)).allSatisfy((table, rows) -> assertThat(rows).as(table).isZero());
        assertThat(footprint(control)).isEqualTo(controlBefore);
        assertThat(n("SELECT COUNT(*) FROM user_notifications WHERE id = ?", similar)).isOne();
        assertThat(Files.exists(recordingFile(target.recording()))).isFalse();
        assertThat(Files.exists(recordingFile(control.recording()))).isTrue();

        Map<String, Object> audit = jdbc.queryForMap(
            "SELECT * FROM audit_logs WHERE action = 'LEAD_DELETED' AND entity_id = ?", target.leadId());
        assertThat(audit.get("user_id")).isEqualTo(sa.getId());
        assertThat(audit.get("entity_type")).isEqualTo("Lead");
        String details = String.valueOf(audit.get("details_json"));
        assertThat(details).contains("\"source\":\"WEBSITE\"").contains("\"mode\":\"SINGLE\"");
        String all = audit.values().toString();
        assertThat(all).doesNotContain("Alisher").doesNotContain("Valiyev")
            .doesNotContain(targetPhone).doesNotContain(targetPhone.substring(4));
        assertThat(audit.get("entity_label")).isNull();

        mvc.perform(delete("/api/leads/" + target.leadId()).with(as(sa)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("lead.delete.notFound"));
    }

    @Test
    void delete_guards_convertedAndPayment_409_nothingDeleted() throws Exception {
        User sa = staff(UserRole.SUPER_ADMIN);
        User sm = staff(UserRole.SALES_MANAGER);

        Long converted = lead("Konvert Qilingan", phone(), sm);
        jdbc.update("UPDATE leads SET converted = TRUE WHERE id = ?", converted);
        Long historyConverted = lead("Tarixda Konvert", phone(), sm);
        inTx(() -> {
            em.persist(LeadStatusHistory.builder().lead(em.find(Lead.class, historyConverted)).toStatus("CONVERTED_OFFLINE")
                .changedAt(LocalDateTime.now()).build());
            return null;
        });
        Long withAmount = lead("Summasi Bor", phone(), sm);
        jdbc.update("UPDATE leads SET amount = 500000 WHERE id = ?", withAmount);
        Long paidStage = lead("Tolov Bosqichi", phone(), sm);
        jdbc.update("UPDATE leads SET status = 'OFFLINE_PAID' WHERE id = ?", paidStage);
        Long paidComment = lead("Tolov Izohi", phone(), sm);
        inTx(() -> {
            em.persist(LeadComment.builder().lead(em.find(Lead.class, paidComment)).author(em.find(User.class, sm.getId()))
                .text("To'lov qabul qilindi: 500 000")
                .createdAt(LocalDateTime.now()).build());
            return null;
        });

        for (Long id : List.of(converted, historyConverted)) {
            mvc.perform(delete("/api/leads/" + id).with(as(sa)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("lead.delete.converted"));
        }
        for (Long id : List.of(withAmount, paidStage, paidComment)) {
            mvc.perform(delete("/api/leads/" + id).with(as(sa)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("lead.delete.hasPayment"));
        }
        assertThat(n("SELECT COUNT(*) FROM leads WHERE id IN (?, ?, ?, ?, ?)", converted, historyConverted, withAmount,
            paidStage, paidComment)).isEqualTo(5);
        assertThat(n("SELECT COUNT(*) FROM lead_comments WHERE lead_id = ?", paidComment)).isOne();
        assertThat(n("SELECT COUNT(*) FROM audit_logs WHERE action = 'LEAD_DELETED'")).isZero();
    }

    // ── Ommaviy ──────────────────────────────────────────────────────────

    @Test
    void bulkDelete_deletedAndSkippedWithReasons_limitAndRole() throws Exception {
        User sa = staff(UserRole.SUPER_ADMIN);
        User sm = staff(UserRole.SALES_MANAGER);
        Long a = lead("Birinchi Lid", phone(), sm);
        Long b = lead("Ikkinchi Lid", phone(), sm);
        Long conv = lead("Konvert Lid", phone(), sm);
        jdbc.update("UPDATE leads SET student_id = NULL, converted_at = ? WHERE id = ?", LocalDateTime.now(), conv);
        long missing = 987_654_321L;

        String body = mvc.perform(post("/api/leads/bulk-delete").with(as(sa)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"leadIds\":[" + a + "," + b + "," + conv + "," + missing + "," + a + "]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.requested").value(4))
            .andExpect(jsonPath("$.data.deletedCount").value(2))
            .andReturn().getResponse().getContentAsString();
        List<Number> deleted = JsonPath.read(body, "$.data.deleted");
        assertThat(deleted.stream().map(Number::longValue).toList()).containsExactly(a, b);
        List<Map<String, Object>> skipped = JsonPath.read(body, "$.data.skipped");
        assertThat(skipped.stream().collect(Collectors.toMap(m -> ((Number) m.get("leadId")).longValue(),
            m -> (String) m.get("reason"))))
            .containsExactlyInAnyOrderEntriesOf(Map.of(conv, "lead.delete.converted", missing, "lead.delete.notFound"));
        assertThat(n("SELECT COUNT(*) FROM leads WHERE id IN (?, ?)", a, b)).isZero();
        assertThat(n("SELECT COUNT(*) FROM audit_logs WHERE action = 'LEAD_DELETED' AND details_json LIKE '%BULK%'"))
            .isEqualTo(2);

        String tooMany = LongStream.rangeClosed(1, LeadHardDeleteService.BULK_MAX + 1).mapToObj(String::valueOf)
            .collect(Collectors.joining(","));
        mvc.perform(post("/api/leads/bulk-delete").with(as(sa)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"leadIds\":[" + tooMany + "]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("lead.bulkDelete.tooMany"));
        mvc.perform(post("/api/leads/bulk-delete").with(as(sa)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"leadIds\":[]}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/leads/bulk-delete").with(as(staff(UserRole.SALES_HEAD)))
                .contentType(MediaType.APPLICATION_JSON).content("{\"leadIds\":[" + conv + "]}"))
            .andExpect(status().isForbidden());
    }

    /**
     * pgtest: leads / tasks / calls ga FK bilan bog'langan har bir ustun servis (va skript) ruxsat ro'yxatida —
     * yangi jadval qo'shilsa shu test yiqiladi (skript §1 dagi pg_constraint tekshiruvi bilan bir xil so'rov).
     */
    @Test
    void pgtest_noUnknownForeignKeysToLeadsTasksCalls() throws Exception {
        String product;
        try (var c = jdbc.getDataSource().getConnection()) {
            product = c.getMetaData().getDatabaseProductName();
        }
        Assumptions.assumeTrue(product.toLowerCase().contains("postgres"), "faqat PostgreSQL (pgtest)");
        List<String> fks = jdbc.queryForList("""
                SELECT DISTINCT format('%s.%s', c.conrelid::regclass, a.attname)
                FROM pg_constraint c
                CROSS JOIN LATERAL unnest(c.conkey) AS k(attnum)
                JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum
                WHERE c.contype = 'f'
                  AND c.confrelid IN (SELECT to_regclass(t) FROM unnest(ARRAY['public.leads', 'public.tasks',
                                      'public.calls']) t WHERE to_regclass(t) IS NOT NULL)
                """, String.class);
        assertThat(fks).isNotEmpty().allSatisfy(fk -> assertThat(LeadHardDeleteService.KNOWN_FKS).contains(fk));
    }
}
