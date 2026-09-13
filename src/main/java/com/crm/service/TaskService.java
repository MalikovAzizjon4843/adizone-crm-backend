package com.crm.service;

import com.crm.audit.AuditAction;
import com.crm.audit.AuditContext;
import com.crm.audit.Audited;
import com.crm.config.Messages;
import com.crm.dto.request.TaskCompleteRequest;
import com.crm.dto.request.TaskCreateRequest;
import com.crm.dto.request.TaskPostponeRequest;
import com.crm.dto.request.TaskReassignRequest;
import com.crm.dto.request.TaskUpdateRequest;
import com.crm.dto.response.PageResponse;
import com.crm.dto.response.TaskCompleteResponse;
import com.crm.dto.response.TaskResponse;
import com.crm.dto.response.TaskStatsResponse;
import com.crm.dto.response.TaskUserStatsDto;
import com.crm.entity.Lead;
import com.crm.entity.Student;
import com.crm.entity.Task;
import com.crm.entity.User;
import com.crm.entity.enums.LeadStatus;
import com.crm.entity.enums.LeadTaskState;
import com.crm.entity.enums.TaskStatus;
import com.crm.entity.enums.TaskType;
import com.crm.exception.BadRequestException;
import com.crm.exception.ForbiddenException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.LeadRepository;
import com.crm.repository.StudentRepository;
import com.crm.repository.TaskRepository;
import com.crm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Operator vazifalari — amoCRM "Задачи" bo'limining backend qismi.
 *
 * <p><b>Ko'rish doirasi</b> {@link LeadAccessService} orqali: ADMIN/SUPER_ADMIN
 * hammasini, SALES_MANAGER faqat o'ziga biriktirilgan yoki o'zi yaratgan
 * vazifalarni ko'radi.
 *
 * <p><b>Muddat bilan ishlash.</b> "Kun davomida" vazifa {@code dueAt = 23:59}
 * bo'lib saqlanadi ({@link #normalizeDueAt}), shuning uchun na so'rovlarda,
 * na hisoblarda {@code allDay} uchun alohida shart yozilmaydi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TaskService {

    /** "Kun davomida" vazifa shu vaqtga keltiriladi. */
    private static final LocalTime ALL_DAY_DUE = LocalTime.of(23, 59);

    private final TaskRepository taskRepository;
    private final LeadRepository leadRepository;
    private final StudentRepository studentRepository;
    private final UserRepository userRepository;
    private final LeadAccessService leadAccessService;
    private final Messages messages;

    // ── Yozish ───────────────────────────────────────────────────────────

    @Transactional
    @Audited(action = AuditAction.CREATE, entity = "Task",
        summary = "'Vazifa yaratildi: ' + #result.title",
        entityId = "#result.id",
        label = "#result.title")
    public TaskResponse create(TaskCreateRequest request) {
        User current = leadAccessService.getCurrentUserOrThrow();
        Optional<Long> scope = leadAccessService.resolveOperatorScope();

        // DTO dagi @AssertTrue bilan bir xil shart. Takrorlanishi ataylab:
        // servis boshqa chaqiruv nuqtasidan ham chaqirilishi mumkin, xabar esa
        // ikkalasida bitta kalitdan keladi.
        Lead lead = null;
        Student student = null;
        if (request.getLeadId() != null && request.getStudentId() != null) {
            throw new BadRequestException(messages.get("task.target.single"));
        }
        if (request.getLeadId() != null) {
            lead = leadRepository.findById(request.getLeadId())
                .orElseThrow(() -> new ResourceNotFoundException("Lead", request.getLeadId()));
            leadAccessService.assertCanAccessLead(lead);
        } else if (request.getStudentId() != null) {
            student = studentRepository.findById(request.getStudentId())
                .orElseThrow(() -> new ResourceNotFoundException("Student", request.getStudentId()));
        }
        // Ikkalasi ham null — mustaqil vazifa, hech qanday obyektga bog'lanmaydi

        User assignee = resolveAssignee(request.getAssignedTo(), current, scope);
        boolean allDay = Boolean.TRUE.equals(request.getAllDay());

        Task task = Task.builder()
            .title(request.getTitle().trim())
            .description(trimToNull(request.getDescription()))
            .type(request.getType() != null ? requireType(request.getType()) : TaskType.CALL)
            .status(TaskStatus.OPEN)
            .dueAt(normalizeDueAt(request.getDueAt(), allDay))
            .allDay(allDay)
            .assignedTo(assignee)
            .createdBy(current)
            .lead(lead)
            .student(student)
            .build();

        return toResponse(taskRepository.save(task));
    }

    @Transactional
    @Audited(action = AuditAction.UPDATE, entity = "Task",
        summary = "'Vazifa tahrirlandi: ' + #result.title",
        entityId = "#result.id",
        label = "#result.title")
    public TaskResponse update(Long id, TaskUpdateRequest request) {
        Task task = getOpenTaskOrThrow(id);

        if (request.getTitle() != null && !request.getTitle().isBlank()) {
            AuditContext.change("title", task.getTitle(), request.getTitle().trim());
            task.setTitle(request.getTitle().trim());
        }
        if (request.getDescription() != null) {
            task.setDescription(trimToNull(request.getDescription()));
        }
        if (request.getType() != null) {
            TaskType type = requireType(request.getType());
            AuditContext.change("type", task.getType(), type);
            task.setType(type);
        }
        if (request.getDueAt() != null) {
            boolean allDay = request.getAllDay() != null
                ? request.getAllDay()
                : Boolean.TRUE.equals(task.getAllDay());
            LocalDateTime dueAt = normalizeDueAt(request.getDueAt(), allDay);
            AuditContext.change("dueAt", task.getDueAt(), dueAt);
            task.setAllDay(allDay);
            task.setDueAt(dueAt);
        } else if (request.getAllDay() != null
                && request.getAllDay() != Boolean.TRUE.equals(task.getAllDay())) {
            // Faqat "kun davomida" belgisi o'zgardi — muddat shunga moslashtiriladi
            task.setAllDay(request.getAllDay());
            task.setDueAt(normalizeDueAt(task.getDueAt(), request.getAllDay()));
        }

        return toResponse(taskRepository.save(task));
    }

    /**
     * Vazifani natija bilan yopadi va so'ralgan bo'lsa darhol keyingisini
     * yaratadi.
     *
     * <p>Ikkalasi shu metodning bitta {@code @Transactional} chegarasida
     * bajariladi: keyingi vazifa yaratishda xato chiqsa, yopish ham
     * rollback bo'ladi va yarim holat qolmaydi. {@code @Audited} esa faqat
     * commitdan keyin yozadi, shuning uchun bekor bo'lgan amal jurnalga
     * tushmaydi.
     *
     * <p>Javobdagi {@code leadHasOpenTask = false} — frontend uchun signal:
     * lid ochiq qoldi, lekin unda rejalashtirilgan qadam yo'q. Shu paytda
     * "yangi vazifa qo'shing" oynasi ko'rsatiladi.
     */
    @Transactional
    @Audited(action = AuditAction.TASK_DONE, entity = "Task",
        summary = "'Vazifa bajarildi: ' + #result.task.title",
        entityId = "#result.task.id",
        label = "#result.task.title")
    public TaskCompleteResponse complete(Long id, TaskCompleteRequest request) {
        Task task = getOpenTaskOrThrow(id);
        User current = leadAccessService.getCurrentUserOrThrow();

        String result = request != null ? trimToNull(request.getResult()) : null;
        if (result == null) {
            throw new BadRequestException("Bajarilish natijasi majburiy");
        }

        task.setStatus(TaskStatus.DONE);
        task.setResult(result);
        task.setCompletedAt(LocalDateTime.now());
        task.setCompletedBy(current);
        Task saved = taskRepository.save(task);

        AuditContext.change("status", TaskStatus.OPEN, TaskStatus.DONE);
        AuditContext.change("result", null, result);

        Task next = request.getNextTask() != null
            ? createFollowUp(saved, request.getNextTask(), current)
            : null;

        Long leadId = saved.getLead() != null ? saved.getLead().getId() : null;
        // Yangi vazifa lidni bajarilgandan meros olgani uchun, u yaratilgan
        // bo'lsa lidda ochiq vazifa borligi ta'rif bo'yicha aniq — bazaga
        // qayta murojaat qilib flush tartibiga tayanmaymiz.
        boolean leadHasOpenTask = leadId != null
            && (next != null
                || taskRepository.existsByLead_IdAndStatus(leadId, TaskStatus.OPEN));

        return TaskCompleteResponse.builder()
            .task(toResponse(saved))
            .leadId(leadId)
            .leadHasOpenTask(leadHasOpenTask)
            .nextTask(next != null ? toResponse(next) : null)
            .build();
    }

    /**
     * Bajarilgan vazifadan zanjirni davom ettiradi.
     *
     * <p>Lid, o'quvchi va mas'ul bajarilgan vazifadan MEROS olinadi:
     * havolalar to'g'ridan-to'g'ri ko'chiriladi, id bo'yicha qayta
     * qidirilmaydi. Shu sababli "lid yoki o'quvchi, ikkalasi emas" qoidasi
     * o'z-o'zidan saqlanadi — manba vazifa uni allaqachon qanoatlantirgan.
     * Mustaqil vazifada ikkalasi ham null bo'lib qoladi.
     *
     * <p>Mas'ul meros olinishi ataylab: admin boshqa operatorning vazifasini
     * yopsa ham zanjir o'sha operatorda qoladi.
     */
    private Task createFollowUp(Task completed, TaskCompleteRequest.NextTask next, User current) {
        TaskType type = next.getType() != null ? requireType(next.getType()) : TaskType.CALL;
        boolean allDay = Boolean.TRUE.equals(next.getAllDay());
        LocalDateTime dueAt = normalizeDueAt(next.getDueAt(), allDay);
        if (!dueAt.isAfter(LocalDateTime.now())) {
            throw new BadRequestException(messages.get("task.dueAt.future"));
        }

        String title = trimToNull(next.getTitle());
        if (title == null) {
            title = type.getLabel();
        }

        Task followUp = Task.builder()
            .title(title)
            .type(type)
            .status(TaskStatus.OPEN)
            .dueAt(dueAt)
            .allDay(allDay)
            .assignedTo(completed.getAssignedTo())
            .createdBy(current)
            .lead(completed.getLead())
            .student(completed.getStudent())
            .build();

        Task savedFollowUp = taskRepository.save(followUp);
        // Alohida audit yozuvi emas: zanjir TASK_DONE yozuvining
        // detailsJson ida ko'rinadi, ikkisi bitta amal.
        AuditContext.change("nextTask", null, title);
        return savedFollowUp;
    }

    @Transactional
    @Audited(action = AuditAction.ASSIGN, entity = "Task",
        summary = "'Vazifa mas''uli: ' + #result.assignedToName",
        entityId = "#result.id",
        label = "#result.title")
    public TaskResponse reassign(Long id, TaskReassignRequest request) {
        Task task = getOpenTaskOrThrow(id);

        User target = userRepository.findById(request.getUserId())
            .orElseThrow(() -> new ResourceNotFoundException("User", request.getUserId()));
        assertCanBeAssignee(target);

        AuditContext.change("assignedTo",
            formatUserName(task.getAssignedTo()), formatUserName(target));
        task.setAssignedTo(target);
        return toResponse(taskRepository.save(task));
    }

    @Transactional
    @Audited(action = AuditAction.UPDATE, entity = "Task",
        summary = "'Vazifa keyinga surildi: ' + #result.title",
        entityId = "#result.id",
        label = "#result.title")
    public TaskResponse postpone(Long id, TaskPostponeRequest request) {
        Task task = getOpenTaskOrThrow(id);

        boolean allDay = request.getAllDay() != null
            ? request.getAllDay()
            : Boolean.TRUE.equals(task.getAllDay());
        LocalDateTime dueAt = normalizeDueAt(request.getDueAt(), allDay);
        if (!dueAt.isAfter(LocalDateTime.now())) {
            throw new BadRequestException("Yangi muddat kelajakda bo'lishi kerak");
        }

        AuditContext.change("dueAt", task.getDueAt(), dueAt);
        task.setAllDay(allDay);
        task.setDueAt(dueAt);
        return toResponse(taskRepository.save(task));
    }

    @Transactional
    @Audited(action = AuditAction.DELETE, entity = "Task", entityId = "#id")
    public void delete(Long id) {
        Task task = taskRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Task", id));

        if (!leadAccessService.hasFullAccess()) {
            User current = leadAccessService.getCurrentUserOrThrow();
            Long authorId = task.getCreatedBy() != null ? task.getCreatedBy().getId() : null;
            if (authorId == null || !authorId.equals(current.getId())) {
                throw new ForbiddenException("Vazifani faqat muallif yoki admin o'chiradi");
            }
        }

        AuditContext.label(task.getTitle());
        AuditContext.summary("Vazifa o'chirildi: " + task.getTitle());
        taskRepository.delete(task);
    }

    // ── O'qish ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<TaskResponse> getAll(
            int page, int size, Long assignedTo, String status, String type,
            Long leadId, Long studentId, String fromDate, String toDate) {
        // SALES_MANAGER uchun assignedTo filtri majburlab qo'yiladi —
        // so'rovdagi qiymat e'tiborga olinmaydi.
        Long effectiveAssignee = leadAccessService.resolveOperatorScope().orElse(assignedTo);

        Pageable pageable = PageRequest.of(
            Math.max(page, 0),
            Math.min(Math.max(size, 1), 100),
            Sort.by("dueAt").ascending());

        Page<Task> tasks = taskRepository.findAll(
            buildSpec(effectiveAssignee, status, type, leadId, studentId, fromDate, toDate),
            pageable);
        return toPageResponse(tasks);
    }

    @Transactional(readOnly = true)
    public TaskResponse getById(Long id) {
        Task task = taskRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Task", id));
        assertCanAccessTask(task);
        return toResponse(task);
    }

    /**
     * Joriy foydalanuvchining ochiq vazifalari.
     *
     * <p>Filtrlar:
     * <ul>
     *   <li>{@code overdue} — muddati o'tganlar;</li>
     *   <li>{@code today} — bugun ichida, hali o'tmaganlar. Muddati o'tganlarni
     *       O'Z ICHIGA OLMAYDI: amoCRM'da ham "Задачи на сегодня" va
     *       "Просроченных" alohida sonlar;</li>
     *   <li>{@code week} — keyingi 7 kun ichidagi hammasi, muddati o'tganlar
     *       BILAN BIRGA. Bu operatorning haqiqiy ish ro'yxati;</li>
     *   <li>{@code all} — barcha ochiqlar.</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public PageResponse<TaskResponse> getMy(String filter, int page, int size) {
        User current = leadAccessService.getCurrentUserOrThrow();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime tomorrow = now.toLocalDate().plusDays(1).atStartOfDay();

        Specification<Task> spec = (root, query, cb) -> cb.and(
            cb.equal(root.get("assignedTo").get("id"), current.getId()),
            cb.equal(root.get("status"), TaskStatus.OPEN));

        String normalized = filter != null && !filter.isBlank()
            ? filter.trim().toLowerCase()
            : "all";
        switch (normalized) {
            case "overdue" -> spec = spec.and((root, query, cb) ->
                cb.lessThan(root.get("dueAt"), now));
            case "today" -> spec = spec.and((root, query, cb) -> cb.and(
                cb.greaterThanOrEqualTo(root.get("dueAt"), now),
                cb.lessThan(root.get("dueAt"), tomorrow)));
            case "week" -> spec = spec.and((root, query, cb) -> cb.lessThan(
                root.get("dueAt"), now.toLocalDate().plusDays(8).atStartOfDay()));
            case "all" -> { }
            default -> throw new BadRequestException(
                "Noto'g'ri filter: " + filter + " (today, overdue, week, all)");
        }

        Pageable pageable = PageRequest.of(
            Math.max(page, 0),
            Math.min(Math.max(size, 1), 100),
            Sort.by("dueAt").ascending());
        return toPageResponse(taskRepository.findAll(spec, pageable));
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> getByLead(Long leadId) {
        leadAccessService.assertCanAccessLead(leadId);
        return taskRepository.findByLeadIdOrdered(leadId).stream()
            .map(this::toResponse)
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public TaskStatsResponse getStats() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime tomorrow = now.toLocalDate().plusDays(1).atStartOfDay();
        Optional<Long> scope = leadAccessService.resolveOperatorScope();

        List<Object[]> buckets = scope
            .map(userId -> taskRepository.countOpenBucketsByUser(now, tomorrow, userId))
            .orElseGet(() -> taskRepository.countOpenBuckets(now, tomorrow));
        Object[] row = buckets.isEmpty() ? new Object[3] : buckets.get(0);

        long noTask = scope
            .map(userId -> leadRepository.countOpenLeadsWithoutTaskByUser(
                LeadStatus.closed(), userId))
            .orElseGet(() -> leadRepository.countOpenLeadsWithoutTask(LeadStatus.closed()));

        List<TaskUserStatsDto> byUser = scope.isPresent()
            ? List.of()
            : taskRepository.countOpenGroupedByUser(now, tomorrow).stream()
                .map(r -> TaskUserStatsDto.builder()
                    .userId((Long) r[0])
                    .name(r[1] != null ? r[1].toString().trim() : "")
                    .overdue(toLong(r[2]))
                    .today(toLong(r[3]))
                    .totalOpen(toLong(r[4]))
                    .build())
                .collect(Collectors.toList());

        return TaskStatsResponse.builder()
            .overdue(toLong(row[0]))
            .today(toLong(row[1]))
            .upcoming(toLong(row[2]))
            .noTask(noTask)
            .byUser(byUser)
            .build();
    }

    // ── Lid ro'yxati uchun batch ────────────────────────────────────────

    /**
     * Lidlarning eng yaqin OCHIQ vazifasi: {@code leadId -> Task}.
     * {@code LeadService} kanban/ro'yxat javobini shu bilan to'ldiradi.
     *
     * <p>Bitta so'rov: natija muddat bo'yicha saralangan, shuning uchun har
     * bir lid uchun birinchi uchragan yozuv eng yaqini bo'ladi. Bu yerda
     * {@code getLead().getId()} lazy proxy'ni yuklamaydi — id allaqachon mavjud.
     */
    @Transactional(readOnly = true)
    public Map<Long, Task> loadNextOpenTasks(List<Long> leadIds) {
        if (leadIds == null || leadIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Task> nextByLead = new HashMap<>();
        for (Task task : taskRepository.findOpenByLeadIds(leadIds)) {
            nextByLead.putIfAbsent(task.getLead().getId(), task);
        }
        return nextByLead;
    }

    // ── Yordamchilar ────────────────────────────────────────────────────

    private Specification<Task> buildSpec(
            Long assignedTo, String status, String type,
            Long leadId, Long studentId, String fromDate, String toDate) {
        Specification<Task> spec = Specification.where(null);

        if (assignedTo != null) {
            spec = spec.and((root, query, cb) ->
                cb.equal(root.get("assignedTo").get("id"), assignedTo));
        }
        if (status != null && !status.isBlank()) {
            TaskStatus value = TaskStatus.parseOrNull(status);
            if (value == null) {
                throw new BadRequestException("Noto'g'ri vazifa statusi: " + status);
            }
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), value));
        }
        if (type != null && !type.isBlank()) {
            TaskType value = requireType(type);
            spec = spec.and((root, query, cb) -> cb.equal(root.get("type"), value));
        }
        if (leadId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("lead").get("id"), leadId));
        }
        if (studentId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("student").get("id"), studentId));
        }
        if (fromDate != null && !fromDate.isBlank()) {
            LocalDate start = parseDateParam(fromDate, "fromDate");
            spec = spec.and((root, query, cb) ->
                cb.greaterThanOrEqualTo(root.get("dueAt"), start.atStartOfDay()));
        }
        if (toDate != null && !toDate.isBlank()) {
            LocalDate end = parseDateParam(toDate, "toDate");
            spec = spec.and((root, query, cb) ->
                cb.lessThan(root.get("dueAt"), end.plusDays(1).atStartOfDay()));
        }
        return spec;
    }

    private Task getOpenTaskOrThrow(Long id) {
        Task task = taskRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Task", id));
        assertCanAccessTask(task);
        if (task.getStatus() != TaskStatus.OPEN) {
            throw new BadRequestException("Vazifa allaqachon yopilgan");
        }
        return task;
    }

    /**
     * To'liq huquq bo'lmasa vazifa FAQAT o'ziga biriktirilgan bo'lishi kerak.
     *
     * <p>Muallif tekshiruvi ataylab yo'q: admin vazifani {@code /reassign}
     * bilan boshqa odamga o'tkazgach, eski muallif {@code TaskResponse}
     * ichidagi lid ma'lumotini ko'rishda davom etardi. Mas'ullik o'tgach
     * ko'rish huquqi ham o'tadi.
     */
    private void assertCanAccessTask(Task task) {
        if (leadAccessService.hasFullAccess()) {
            return;
        }
        User current = leadAccessService.getCurrentUserOrThrow();
        boolean isAssignee = task.getAssignedTo() != null
            && current.getId().equals(task.getAssignedTo().getId());
        if (!isAssignee) {
            throw new ForbiddenException("Bu vazifa sizga tegishli emas");
        }
    }

    private User resolveAssignee(Long requestedId, User current, Optional<Long> scope) {
        if (requestedId == null || requestedId.equals(current.getId())) {
            return current;
        }
        if (scope.isPresent()) {
            throw new ForbiddenException("Vazifani faqat o'zingizga biriktira olasiz");
        }
        User target = userRepository.findById(requestedId)
            .orElseThrow(() -> new ResourceNotFoundException("User", requestedId));
        assertCanBeAssignee(target);
        return target;
    }

    private void assertCanBeAssignee(User target) {
        if (!LeadAccessService.canBeOperator(target.getRole())) {
            throw new BadRequestException(
                "Vazifa faqat ADMIN, SUPER_ADMIN yoki SALES_MANAGER ga biriktiriladi");
        }
        if (!Boolean.TRUE.equals(target.getIsActive())) {
            throw new BadRequestException("Faol bo'lmagan foydalanuvchiga vazifa berilmaydi");
        }
    }

    /** "Kun davomida" vazifa kun oxiriga suriladi — qarang {@link Task}. */
    private LocalDateTime normalizeDueAt(LocalDateTime dueAt, boolean allDay) {
        if (dueAt == null) {
            throw new BadRequestException("Muddat majburiy");
        }
        return allDay ? dueAt.toLocalDate().atTime(ALL_DAY_DUE) : dueAt;
    }

    private TaskType requireType(String raw) {
        TaskType type = TaskType.parseOrNull(raw);
        if (type == null) {
            throw new BadRequestException("Noto'g'ri vazifa turi: " + raw);
        }
        return type;
    }

    private LocalDate parseDateParam(String value, String paramName) {
        try {
            return LocalDate.parse(value.trim());
        } catch (Exception e) {
            throw new BadRequestException(
                "Noto'g'ri " + paramName + " formati (yyyy-MM-dd): " + value);
        }
    }

    private PageResponse<TaskResponse> toPageResponse(Page<Task> tasks) {
        return PageResponse.<TaskResponse>builder()
            .content(tasks.getContent().stream().map(this::toResponse).collect(Collectors.toList()))
            .pageNumber(tasks.getNumber())
            .pageSize(tasks.getSize())
            .totalElements(tasks.getTotalElements())
            .totalPages(tasks.getTotalPages())
            .last(tasks.isLast())
            .build();
    }

    private TaskResponse toResponse(Task task) {
        return TaskResponse.builder()
            .id(task.getId())
            .uuid(task.getUuid())
            .title(task.getTitle())
            .description(task.getDescription())
            .type(task.getType())
            .typeLabel(task.getType() != null ? task.getType().getLabel() : null)
            .status(task.getStatus())
            .statusLabel(task.getStatus() != null ? task.getStatus().getLabel() : null)
            .dueAt(task.getDueAt())
            .allDay(task.getAllDay())
            .state(task.getStatus() == TaskStatus.OPEN
                ? LeadTaskState.resolve(task.getDueAt(), LocalDateTime.now())
                : LeadTaskState.NONE)
            .assignedToId(task.getAssignedTo() != null ? task.getAssignedTo().getId() : null)
            .assignedToName(formatUserName(task.getAssignedTo()))
            .createdById(task.getCreatedBy() != null ? task.getCreatedBy().getId() : null)
            .createdByName(formatUserName(task.getCreatedBy()))
            .leadId(task.getLead() != null ? task.getLead().getId() : null)
            .leadName(task.getLead() != null ? task.getLead().getFullName() : null)
            .studentId(task.getStudent() != null ? task.getStudent().getId() : null)
            .studentName(formatStudentName(task.getStudent()))
            .completedAt(task.getCompletedAt())
            .completedById(task.getCompletedBy() != null ? task.getCompletedBy().getId() : null)
            .completedByName(formatUserName(task.getCompletedBy()))
            .result(task.getResult())
            .createdAt(task.getCreatedAt())
            .updatedAt(task.getUpdatedAt())
            .build();
    }

    private static String formatUserName(User user) {
        if (user == null) {
            return null;
        }
        String name = ((user.getFirstName() != null ? user.getFirstName() : "")
            + " " + (user.getLastName() != null ? user.getLastName() : "")).trim();
        return name.isEmpty() ? null : name;
    }

    private static String formatStudentName(Student student) {
        if (student == null) {
            return null;
        }
        String name = ((student.getFirstName() != null ? student.getFirstName() : "")
            + " " + (student.getLastName() != null ? student.getLastName() : "")).trim();
        return name.isEmpty() ? null : name;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static long toLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }
}
