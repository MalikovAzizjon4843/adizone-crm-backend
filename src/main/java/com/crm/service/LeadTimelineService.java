package com.crm.service;

import com.crm.audit.AuditAction;
import com.crm.dto.response.LeadNoteResponse;
import com.crm.dto.response.LeadStatusHistoryResponse;
import com.crm.dto.response.LeadTimelineItemDto;
import com.crm.dto.response.LeadTimelineResponse;
import com.crm.dto.response.TaskResponse;
import com.crm.entity.AuditLog;
import com.crm.entity.Lead;
import com.crm.entity.LeadComment;
import com.crm.entity.Task;
import com.crm.entity.enums.TaskStatus;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.AuditLogRepository;
import com.crm.repository.LeadCommentRepository;
import com.crm.repository.LeadNoteRepository;
import com.crm.repository.LeadRepository;
import com.crm.repository.LeadStatusHistoryRepository;
import com.crm.repository.TaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Lid kartasining yagona xronologik lentasi — amoCRM'dagi "lenta".
 *
 * <p>Oltita manba alohida-alohida bitta so'rov bilan olinadi va xotirada
 * birlashtiriladi. Bazada UNION qilinmaydi: yozuvlar turli jadvallarda,
 * turli ustunlar bilan va bitta lid uchun soni o'nlab, ko'pi bilan yuzlab.
 *
 * <p>So'rovlar soni turga emas, MANBAGA bog'liq — vazifalar bitta
 * so'rovdan kelib, ham TASK_CREATED, ham TASK_COMPLETED yozuvlarini
 * beradi, ochiq vazifalar ro'yxati ham o'sha ro'yxatdan ajratiladi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LeadTimelineService {

    public static final String TYPE_TASK_COMPLETED = "TASK_COMPLETED";
    public static final String TYPE_TASK_CREATED = "TASK_CREATED";
    public static final String TYPE_STATUS_CHANGED = "STATUS_CHANGED";
    public static final String TYPE_NOTE = "NOTE";
    public static final String TYPE_ASSIGNEE_CHANGED = "ASSIGNEE_CHANGED";
    public static final String TYPE_LEAD_CREATED = "LEAD_CREATED";

    /** {@code AuditContext.change} yozadigan JSON kalitlari. */
    private static final String CHANGES_KEY = "changes";
    private static final String FIELD_KEY = "field";
    private static final String OLD_KEY = "old";
    private static final String NEW_KEY = "new";

    /** {@code LeadService.assignLead} shu nom bilan o'zgarish yozadi. */
    private static final String ASSIGNED_USER_FIELD = "assignedUser";

    private static final int MAX_PAGE_SIZE = 200;

    private final LeadRepository leadRepository;
    private final TaskRepository taskRepository;
    private final AuditLogRepository auditLogRepository;
    private final LeadCommentRepository leadCommentRepository;
    private final LeadNoteRepository leadNoteRepository;
    private final LeadStatusHistoryRepository leadStatusHistoryRepository;
    private final LeadService leadService;
    private final TaskService taskService;
    private final LeadAccessService leadAccessService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public LeadTimelineResponse getTimeline(Long leadId, int page, int size) {
        Lead lead = leadRepository.findById(leadId)
            .orElseThrow(() -> new ResourceNotFoundException("Lead", leadId));
        leadAccessService.assertCanAccessLead(lead);

        // Bitta so'rov — ochiqlar yuqorida, keyin yopilganlar
        List<Task> tasks = taskRepository.findByLeadIdOrdered(leadId);

        // Ruxsat yuqorida bir marta tekshirildi. leadService.getHistory()/
        // getNotes() o'rniga repositorylarga to'g'ridan-to'g'ri boramiz:
        // o'sha metodlar har biri ruxsatni qaytadan tekshirib, qo'shimcha
        // users lookup yuborardi. Mapping esa LeadService da qoladi.
        List<LeadTimelineItemDto> items = new ArrayList<>();
        addTaskItems(items, tasks);
        addStatusItems(items, leadService.toHistoryResponses(
            leadStatusHistoryRepository.findByLead_IdOrderByChangedAtDesc(leadId)));
        addNoteItems(items, leadService.toNoteResponses(
            leadNoteRepository.findByLead_IdOrderByCreatedAtDesc(leadId)));
        addCommentItems(items, leadCommentRepository.findByLeadIdOrderByCreatedAtDesc(leadId));
        addAssigneeItems(items, auditLogRepository
            .findByEntityTypeAndEntityIdAndActionOrderByCreatedAtDesc(
                "Lead", leadId, AuditAction.ASSIGN));
        addLeadCreatedItem(items, lead);

        // Yangi birinchi. Sanasi yo'q yozuv oxirida qoladi — bunday holat
        // faqat buzilgan ma'lumotda uchraydi, lekin saralashni yiqitmasin.
        items.sort(Comparator.comparing(
            LeadTimelineItemDto::getAt,
            Comparator.nullsLast(Comparator.<LocalDateTime>reverseOrder())));

        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        int from = Math.min(safePage * safeSize, items.size());
        int to = Math.min(from + safeSize, items.size());
        int totalPages = (int) Math.ceil((double) items.size() / safeSize);

        return LeadTimelineResponse.builder()
            .items(new ArrayList<>(items.subList(from, to)))
            .page(LeadTimelineResponse.PageInfo.builder()
                .pageNumber(safePage)
                .pageSize(safeSize)
                .totalElements(items.size())
                .totalPages(totalPages)
                .last(to >= items.size())
                .build())
            .openTasks(openTasks(tasks))
            .build();
    }

    // ── Manbalar ────────────────────────────────────────────────────────

    /** Bitta vazifa ikkita yozuv beradi: yaratilgani va (yopilgan bo'lsa) bajarilgani. */
    private void addTaskItems(List<LeadTimelineItemDto> items, List<Task> tasks) {
        for (Task task : tasks) {
            items.add(LeadTimelineItemDto.builder()
                .type(TYPE_TASK_CREATED)
                .at(task.getCreatedAt())
                .actorId(idOf(task.getCreatedBy()))
                .actorName(nameOf(task.getCreatedBy()))
                .title(task.getTitle())
                .taskType(task.getType())
                .taskTypeLabel(task.getType() != null ? task.getType().getLabel() : null)
                .dueAt(task.getDueAt())
                .toValue(nameOf(task.getAssignedTo()))
                .refId(task.getId())
                .build());

            if (task.getStatus() == TaskStatus.DONE) {
                items.add(LeadTimelineItemDto.builder()
                    .type(TYPE_TASK_COMPLETED)
                    .at(task.getCompletedAt())
                    .actorId(idOf(task.getCompletedBy()))
                    .actorName(nameOf(task.getCompletedBy()))
                    .title(task.getTitle())
                    .text(task.getResult())
                    .taskType(task.getType())
                    .taskTypeLabel(task.getType() != null ? task.getType().getLabel() : null)
                    .refId(task.getId())
                    .build());
            }
        }
    }

    private void addStatusItems(List<LeadTimelineItemDto> items,
                                List<LeadStatusHistoryResponse> history) {
        for (LeadStatusHistoryResponse row : history) {
            items.add(LeadTimelineItemDto.builder()
                .type(TYPE_STATUS_CHANGED)
                .at(row.getChangedAt())
                .actorId(row.getChangedById())
                .actorName(row.getChangedByName())
                .text(row.getNote())
                .fromValue(row.getFromStatusLabel())
                .toValue(row.getToStatusLabel())
                .daysInPrevious(row.getDaysInPreviousStatus())
                .refId(row.getId())
                .build());
        }
    }

    private void addNoteItems(List<LeadTimelineItemDto> items, List<LeadNoteResponse> notes) {
        for (LeadNoteResponse note : notes) {
            items.add(LeadTimelineItemDto.builder()
                .type(TYPE_NOTE)
                .at(note.getCreatedAt())
                .actorId(note.getCreatedById())
                .actorName(note.getCreatedByName())
                .text(note.getText())
                .refId(note.getId())
                .editable(true)
                .build());
        }
    }

    /**
     * Eski {@code lead_comments} yozuvlari — lentada NOTE sifatida, lekin
     * {@code editable = false}.
     *
     * <p>Ular ko'chirilmaydi: jadval o'z o'rnida qoladi va {@code updateStatus}
     * hamon unga avtomatik izoh yozadi ("To'lov qabul qilindi"). Shuning uchun
     * bu manba tarixiy emas, hozir ham to'ldirilib turadi.
     *
     * <p>{@code refId} bu yerda {@code lead_comments} id si — {@code lead_notes}
     * bilan kesishishi mumkin. Frontend {@code editable} ga qarab ajratadi,
     * backend esa izoh endpointlarida faqat {@code LeadNoteRepository} ga
     * murojaat qiladi, ya'ni noto'g'ri jadvalga yozib yuborish imkonsiz.
     */
    private void addCommentItems(List<LeadTimelineItemDto> items, List<LeadComment> comments) {
        for (LeadComment comment : comments) {
            items.add(LeadTimelineItemDto.builder()
                .type(TYPE_NOTE)
                .at(comment.getCreatedAt())
                .actorId(idOf(comment.getAuthor()))
                .actorName(nameOf(comment.getAuthor()))
                .text(comment.getText())
                .refId(comment.getId())
                .editable(false)
                .build());
        }
    }

    /**
     * Mas'ul almashuvi audit jurnalidan olinadi — alohida jadval yo'q.
     *
     * <p>Audit 90 kun saqlanadi ({@code AuditRetentionJob}), shuning uchun
     * eski lidda bu yozuvlar bo'lmasligi normal holat.
     */
    private void addAssigneeItems(List<LeadTimelineItemDto> items, List<AuditLog> logs) {
        for (AuditLog log : logs) {
            Map<String, Object> change = findChange(log.getDetailsJson(), ASSIGNED_USER_FIELD);
            items.add(LeadTimelineItemDto.builder()
                .type(TYPE_ASSIGNEE_CHANGED)
                .at(log.getCreatedAt())
                .actorId(log.getUserId())
                .actorName(log.getUsername())
                .fromValue(change != null ? asText(change.get(OLD_KEY)) : null)
                .toValue(change != null ? asText(change.get(NEW_KEY)) : null)
                .refId(log.getId())
                .build());
        }
    }

    private void addLeadCreatedItem(List<LeadTimelineItemDto> items, Lead lead) {
        items.add(LeadTimelineItemDto.builder()
            .type(TYPE_LEAD_CREATED)
            .at(lead.getCreatedAt())
            .actorId(idOf(lead.getCreatedBy()))
            .actorName(nameOf(lead.getCreatedBy()))
            .title(lead.getFullName())
            .toValue(lead.getSource())
            .refId(lead.getId())
            .build());
    }

    /** Lenta uchun olingan ro'yxatdan ajratiladi — qo'shimcha so'rov yo'q. */
    private List<TaskResponse> openTasks(List<Task> tasks) {
        return tasks.stream()
            .filter(t -> t.getStatus() == TaskStatus.OPEN)
            .sorted(Comparator.comparing(
                Task::getDueAt, Comparator.nullsLast(Comparator.<LocalDateTime>naturalOrder())))
            .map(taskService::toResponse)
            .toList();
    }

    // ── Yordamchilar ────────────────────────────────────────────────────

    /**
     * {@code detailsJson} ichidan berilgan maydon o'zgarishini topadi.
     * Shakl: {@code {"changes":[{"field":"...","old":...,"new":...}]}}.
     *
     * <p>Buzilgan JSON lentani yiqitmasligi kerak — xato faqat loglanadi.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> findChange(String detailsJson, String field) {
        if (detailsJson == null || detailsJson.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(detailsJson, Map.class);
            Object changes = payload.get(CHANGES_KEY);
            if (!(changes instanceof List<?> rows)) {
                return null;
            }
            for (Object row : rows) {
                if (row instanceof Map<?, ?> map && field.equals(map.get(FIELD_KEY))) {
                    return (Map<String, Object>) map;
                }
            }
        } catch (Exception e) {
            log.warn("Audit detailsJson o'qilmadi (auditLog details): {}", e.getMessage());
        }
        return null;
    }

    private static String asText(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    private static Long idOf(com.crm.entity.User user) {
        return user != null ? user.getId() : null;
    }

    private static String nameOf(com.crm.entity.User user) {
        if (user == null) {
            return null;
        }
        String name = ((user.getFirstName() != null ? user.getFirstName() : "")
            + " " + (user.getLastName() != null ? user.getLastName() : "")).trim();
        return name.isEmpty() ? null : name;
    }
}
