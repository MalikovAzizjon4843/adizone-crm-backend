package com.crm.service;

import com.crm.audit.AuditAction;
import com.crm.audit.AuditContext;
import com.crm.audit.Audited;
import com.crm.dto.request.LeadAssignRequest;
import com.crm.dto.request.LeadCommentRequest;
import com.crm.dto.request.LeadNoteRequest;
import com.crm.dto.request.LeadConvertRequest;
import com.crm.dto.request.LeadCreateRequest;
import com.crm.dto.request.LeadRequest;
import com.crm.dto.request.StudentGroupRequest;
import com.crm.dto.response.LeadCommentResponse;
import com.crm.dto.response.LeadNoteResponse;
import com.crm.dto.response.LeadConvertResponse;
import com.crm.dto.response.LeadOperatorResponse;
import com.crm.dto.response.LeadOperatorStatsResponse;
import com.crm.dto.response.LeadResponse;
import com.crm.dto.response.LeadKanbanColumnDto;
import com.crm.dto.response.LeadKanbanStatsResponse;
import com.crm.dto.response.LeadStatsResponse;
import com.crm.dto.response.LeadStatusHistoryResponse;
import com.crm.dto.response.PageResponse;
import com.crm.entity.Lead;
import com.crm.entity.LeadComment;
import com.crm.entity.LeadNote;
import com.crm.entity.LeadStatusHistory;
import com.crm.entity.Student;
import com.crm.entity.Task;
import com.crm.entity.User;
import com.crm.entity.enums.LeadStatus;
import com.crm.entity.enums.LeadTaskState;
import com.crm.entity.enums.MarketingSource;
import com.crm.entity.enums.PaymentStatus;
import com.crm.entity.enums.StudentStatus;
import com.crm.exception.BadRequestException;
import com.crm.exception.ForbiddenException;
import com.crm.exception.DuplicateResourceException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.LeadCommentRepository;
import com.crm.repository.LeadNoteRepository;
import com.crm.repository.LeadRepository;
import com.crm.repository.LeadStatusHistoryRepository;
import com.crm.repository.StudentRepository;
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
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class LeadService {

    private static final String PAYMENT_COMMENT_TEXT = "To'lov qabul qilindi";

    private final LeadRepository leadRepository;
    private final LeadCommentRepository leadCommentRepository;
    private final LeadNoteRepository leadNoteRepository;
    private final LeadStatusHistoryRepository leadStatusHistoryRepository;
    private final StudentRepository studentRepository;
    private final StudentService studentService;
    private final GroupService groupService;
    private final UserRepository userRepository;
    private final TaskService taskService;
    private final LeadAccessService leadAccessService;

    @Transactional
    @Audited(action = AuditAction.CREATE, entity = "Lead",
        summary = "'Yangi lid: ' + #result.fullName",
        entityId = "#result.id",
        label = "#result.fullName")
    public LeadResponse createLead(LeadRequest request) {
        Lead lead = Lead.builder()
                .fullName(request.getFullName())
                .phone(request.getPhone())
                .parentPhone(request.getParentPhone())
                .address(request.getAddress())
                .course(request.getCourse())
                .format(request.getFormat() != null
                        ? request.getFormat().toUpperCase()
                        : "OFFLINE")
                .source(request.getSource() != null
                        ? request.getSource().toUpperCase()
                        : "WEBSITE")
                .notes(request.getNotes())
                .status(LeadStatus.NEW)
                .converted(false)
                .build();
        return toResponse(leadRepository.save(lead));
    }

    /**
     * Xodim tomonidan lid yaratish — kanbandagi tez qo'shish uchun.
     *
     * <p>{@link #createLead} dan farqi: bosqich va operator berilishi mumkin,
     * {@code createdBy} to'ldiriladi. Ochiq forma oqimi tegilmagan.
     *
     * <p>Telefon dublikati TEKSHIRILMAYDI — {@code /public} da ham
     * tekshirilmaydi va ikkisi bir xil qoidada qolishi kerak. Bu ataylab
     * qoldirilgan bo'shliq: {@code leads.phone} ustunida unique indeks yo'q.
     */
    @Transactional
    @Audited(action = AuditAction.CREATE, entity = "Lead",
        summary = "'Yangi lid (qo''lda): ' + #result.fullName",
        entityId = "#result.id",
        label = "#result.fullName")
    public LeadResponse createLeadByStaff(LeadCreateRequest request) {
        User current = leadAccessService.getCurrentUserOrThrow();
        Optional<Long> scope = leadAccessService.resolveOperatorScope();
        User assignee = resolveLeadAssignee(request.getAssignedUserId(), current, scope);

        Lead lead = Lead.builder()
                .fullName(request.getFullName())
                .phone(request.getPhone())
                .parentPhone(request.getParentPhone())
                .address(request.getAddress())
                .course(request.getCourse())
                .format(request.getFormat() != null
                        ? request.getFormat().toUpperCase()
                        : "OFFLINE")
                .source(request.getSource() != null
                        ? request.getSource().toUpperCase()
                        : "WEBSITE")
                .notes(request.getNotes())
                .status(request.getStatus() != null && !request.getStatus().isBlank()
                        ? parseStatus(request.getStatus())
                        : LeadStatus.NEW)
                .assignedUser(assignee)
                .assignedAt(assignee != null ? LocalDateTime.now() : null)
                .createdBy(current)
                .converted(false)
                .build();
        return toResponse(leadRepository.save(lead));
    }

    /**
     * Yangi lid uchun operatorni aniqlaydi.
     *
     * <p>Berilmasa: SALES_MANAGER o'ziga oladi (aks holda o'zi yaratgan lidni
     * darhol yo'qotardi), ADMIN/SUPER_ADMIN da null qoladi va lid
     * "Biriktirilmagan" ustuniga tushadi.
     *
     * <p>Doirali foydalanuvchi boshqa odamga biriktira olmaydi — bu
     * {@code TaskService.resolveAssignee} bilan bir xil qoida.
     */
    private User resolveLeadAssignee(Long requestedId, User current, Optional<Long> scope) {
        if (requestedId == null) {
            return scope.isPresent() ? current : null;
        }
        if (scope.isPresent() && !requestedId.equals(current.getId())) {
            throw new ForbiddenException("Lidni faqat o'zingizga biriktira olasiz");
        }
        User target = userRepository.findById(requestedId)
                .orElseThrow(() -> new ResourceNotFoundException("User", requestedId));
        if (!LeadAccessService.canBeOperator(target.getRole())) {
            throw new BadRequestException(
                "Faqat ADMIN, SUPER_ADMIN yoki SALES_MANAGER operator sifatida biriktiriladi");
        }
        if (!Boolean.TRUE.equals(target.getIsActive())) {
            throw new BadRequestException("Faol bo'lmagan foydalanuvchiga lid biriktirilmaydi");
        }
        return target;
    }

    @Transactional(readOnly = true)
    public PageResponse<LeadResponse> getAll(
            int page, int size, String status, String search,
            Long assignedUserId, Boolean unassigned,
            String fromDate, String toDate) {
        // SALES_MANAGER uchun operator filtri majburlab qo'yiladi: so'rovdagi
        // assignedUserId ham, unassigned ham e'tiborga olinmaydi.
        Optional<Long> scope = leadAccessService.resolveOperatorScope();
        Long effectiveUserId = scope.orElse(assignedUserId);
        Boolean effectiveUnassigned = scope.isPresent() ? null : unassigned;

        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by("createdAt").descending());
        Specification<Lead> spec = buildLeadSpec(
            status, search, effectiveUserId, effectiveUnassigned, fromDate, toDate);
        Page<Lead> leads = leadRepository.findAll(spec, pageable);
        return toPageResponse(leads);
    }

    @Transactional(readOnly = true)
    public LeadResponse getById(Long id) {
        Lead lead = leadRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Lead", id));
        leadAccessService.assertCanAccessLead(lead);
        return toResponse(lead);
    }

    /**
     * Lid bosqichlari tarixi — yangidan eskiga.
     *
     * <p>{@code daysInPreviousStatus} shu yerda hisoblanadi: yozuvlar ketma-ket
     * bo'lgani uchun ikki o'tish orasidagi farq oldingi bosqichda o'tirgan
     * vaqtni beradi. Eng eski yozuvda null — lid yaratilgan paytdan
     * birinchi o'tishgacha bo'lgan davr uchun tarix yozuvi yo'q.
     */
    @Transactional(readOnly = true)
    public List<LeadStatusHistoryResponse> getHistory(Long id) {
        Lead lead = leadRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Lead", id));
        leadAccessService.assertCanAccessLead(lead);

        return toHistoryResponses(
            leadStatusHistoryRepository.findByLead_IdOrderByChangedAtDesc(id));
    }

    /**
     * Bosqich tarixini DTO ga o'giradi. Paket ichida ochiq, chunki
     * {@code LeadTimelineService} ham shu mapperni ishlatadi — lenta
     * ruxsatni bir marta o'zi tekshirib, repositoryga to'g'ridan-to'g'ri
     * murojaat qiladi, mapping esa ikki joyda takrorlanmasin.
     *
     * <p>{@code rows} yangidan eskiga saralangan bo'lishi SHART:
     * {@code daysInPreviousStatus} ikki qo'shni yozuv orasidagi farqdan
     * hisoblanadi.
     */
    List<LeadStatusHistoryResponse> toHistoryResponses(List<LeadStatusHistory> rows) {
        List<LeadStatusHistoryResponse> result = new java.util.ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            LeadStatusHistory row = rows.get(i);
            Long days = null;
            if (i + 1 < rows.size()) {
                days = java.time.Duration.between(
                    rows.get(i + 1).getChangedAt(), row.getChangedAt()).toDays();
            }
            result.add(LeadStatusHistoryResponse.builder()
                .id(row.getId())
                .fromStatus(row.getFromStatus())
                .fromStatusLabel(row.getFromStatus() != null
                    ? translateStatus(row.getFromStatus()) : null)
                .toStatus(row.getToStatus())
                .toStatusLabel(translateStatus(row.getToStatus()))
                .changedById(row.getChangedBy() != null ? row.getChangedBy().getId() : null)
                .changedByName(row.getChangedBy() != null
                    ? formatUserName(row.getChangedBy()) : null)
                .changedAt(row.getChangedAt())
                .note(row.getNote())
                .daysInPreviousStatus(days)
                .build());
        }
        return result;
    }

    @Transactional
    @Audited(action = AuditAction.ASSIGN, entity = "Lead",
        summary = "'Lid operatori: ' + (#result.assignedUserName ?: 'biriktirilmagan')",
        entityId = "#result.id",
        label = "#result.fullName")
    public LeadResponse assignLead(Long id, LeadAssignRequest request) {
        Lead lead = leadRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Lead", id));
        leadAccessService.assertCanAccessLead(lead);
        String previousOperator = lead.getAssignedUser() != null
                ? formatUserName(lead.getAssignedUser()) : null;

        if (request.getUserId() == null) {
            lead.setAssignedUser(null);
            lead.setAssignedAt(null);
        } else {
            User user = userRepository.findById(request.getUserId())
                    .orElseThrow(() -> new ResourceNotFoundException("User", request.getUserId()));
            // Ro'yxat LeadAccessService.OPERATOR_ROLES bilan bir xil bo'lishi SHART:
            // lid operatori bo'la olgan odam unga vazifa mas'uli ham bo'la olishi kerak.
            if (!LeadAccessService.canBeOperator(user.getRole())) {
                throw new BadRequestException(
                    "Faqat ADMIN, SUPER_ADMIN yoki SALES_MANAGER operator sifatida biriktiriladi");
            }
            lead.setAssignedUser(user);
            lead.setAssignedAt(LocalDateTime.now());
        }
        AuditContext.change("assignedUser", previousOperator,
                lead.getAssignedUser() != null ? formatUserName(lead.getAssignedUser()) : null);
        return toResponse(leadRepository.save(lead));
    }

    @Transactional
    @Audited(action = AuditAction.STATUS_CHANGE, entity = "Lead",
        summary = "'Lid bosqichi: ' + #result.status",
        entityId = "#result.id",
        label = "#result.fullName")
    public LeadResponse updateStatus(Long id, String status) {
        Lead lead = leadRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Lead", id));
        leadAccessService.assertCanAccessLead(lead);

        LeadStatus newStatus = parseStatus(status);
        LeadStatus oldStatus = lead.getStatus();
        lead.setStatus(newStatus);

        // Bir xil bosqichga qayta o'tish tarixni ham, auditni ham to'ldirmasin
        if (oldStatus != newStatus) {
            AuditContext.change("status", oldStatus, newStatus);
            recordStatusChange(lead, oldStatus, newStatus, null);
        }

        if (newStatus == LeadStatus.ONLINE_PAID || newStatus == LeadStatus.OFFLINE_PAID) {
            addSystemComment(lead, PAYMENT_COMMENT_TEXT);
        }

        return toResponse(leadRepository.save(lead));
    }

    @Transactional
    @Audited(action = AuditAction.COMMENT, entity = "Lead",
        summary = "'Lidga izoh yozildi'",
        entityId = "#leadId")
    public LeadCommentResponse addComment(Long leadId, LeadCommentRequest request) {
        Lead lead = leadRepository.findById(leadId)
                .orElseThrow(() -> new ResourceNotFoundException("Lead", leadId));
        leadAccessService.assertCanAccessLead(lead);
        AuditContext.label(lead.getFullName());
        User author = getCurrentUser();
        if (author == null) {
            throw new BadRequestException("Foydalanuvchi aniqlanmadi");
        }

        LeadComment comment = LeadComment.builder()
                .lead(lead)
                .author(author)
                .text(request.getText().trim())
                .statusAtComment(lead.getStatus())
                .build();
        return toCommentResponse(leadCommentRepository.save(comment));
    }

    @Transactional(readOnly = true)
    public PageResponse<LeadCommentResponse> getComments(Long leadId, int page, int size) {
        if (!leadRepository.existsById(leadId)) {
            throw new ResourceNotFoundException("Lead", leadId);
        }
        leadAccessService.assertCanAccessLead(leadId);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by("createdAt").descending());
        Page<LeadComment> comments = leadCommentRepository.findByLeadIdOrderByCreatedAtDesc(leadId, pageable);
        return PageResponse.<LeadCommentResponse>builder()
                .content(comments.getContent().stream()
                        .map(this::toCommentResponse)
                        .collect(Collectors.toList()))
                .pageNumber(comments.getNumber())
                .pageSize(comments.getSize())
                .totalElements(comments.getTotalElements())
                .totalPages(comments.getTotalPages())
                .last(comments.isLast())
                .build();
    }

    /**
     * Operator tanlash ro'yxati. Manba {@code assignLead} qabul qiladigan
     * ro'yxat bilan bitta — aks holda ro'yxatdan tanlangan odam 400 qaytarardi.
     */
    // ── Lenta izohlari (LeadNote) ───────────────────────────────────

    @Transactional
    public LeadNoteResponse addNote(Long leadId, LeadNoteRequest request) {
        Lead lead = leadRepository.findById(leadId)
                .orElseThrow(() -> new ResourceNotFoundException("Lead", leadId));
        leadAccessService.assertCanAccessLead(lead);

        LeadNote note = LeadNote.builder()
                .lead(lead)
                .text(request.getText().trim())
                .createdBy(leadAccessService.getCurrentUserOrThrow())
                .build();
        return toNoteResponse(leadNoteRepository.save(note));
    }

    @Transactional(readOnly = true)
    public List<LeadNoteResponse> getNotes(Long leadId) {
        leadAccessService.assertCanAccessLead(leadId);
        return toNoteResponses(leadNoteRepository.findByLead_IdOrderByCreatedAtDesc(leadId));
    }

    /**
     * Izohlarni DTO ga o'giradi. {@link #toHistoryResponses} bilan bir xil
     * sabab: lenta ruxsatni o'zi tekshiradi va repositoryga to'g'ridan-to'g'ri
     * boradi, lekin mapping bitta joyda qoladi.
     */
    List<LeadNoteResponse> toNoteResponses(List<LeadNote> notes) {
        return notes.stream()
                .map(this::toNoteResponse)
                .collect(Collectors.toList());
    }

    /** Izohni faqat muallif tahrirlaydi — admin ham boshqaning matnini o'zgartirmaydi. */
    @Transactional
    public LeadNoteResponse updateNote(Long id, LeadNoteRequest request) {
        LeadNote note = loadNoteOrThrow(id);
        User current = leadAccessService.getCurrentUserOrThrow();
        if (!isNoteAuthor(note, current)) {
            throw new ForbiddenException("Izohni faqat muallif tahrirlaydi");
        }
        note.setText(request.getText().trim());
        return toNoteResponse(leadNoteRepository.save(note));
    }

    /** O'chirish — muallif yoki to'liq huquqli foydalanuvchi (SUPER_ADMIN/ADMIN). */
    @Transactional
    public void deleteNote(Long id) {
        LeadNote note = loadNoteOrThrow(id);
        if (!leadAccessService.hasFullAccess()
                && !isNoteAuthor(note, leadAccessService.getCurrentUserOrThrow())) {
            throw new ForbiddenException("Izohni o'chirishga ruxsat yo'q");
        }
        leadNoteRepository.delete(note);
    }

    private LeadNote loadNoteOrThrow(Long id) {
        LeadNote note = leadNoteRepository.findWithLeadById(id)
                .orElseThrow(() -> new ResourceNotFoundException("LeadNote", id));
        leadAccessService.assertCanAccessLead(note.getLead());
        return note;
    }

    private boolean isNoteAuthor(LeadNote note, User user) {
        return note.getCreatedBy() != null
                && user != null
                && user.getId().equals(note.getCreatedBy().getId());
    }

    private LeadNoteResponse toNoteResponse(LeadNote note) {
        return LeadNoteResponse.builder()
                .id(note.getId())
                .uuid(note.getUuid())
                .leadId(note.getLead() != null ? note.getLead().getId() : null)
                .text(note.getText())
                .createdById(note.getCreatedBy() != null ? note.getCreatedBy().getId() : null)
                .createdByName(note.getCreatedBy() != null
                        ? formatUserName(note.getCreatedBy()) : null)
                .createdAt(note.getCreatedAt())
                .updatedAt(note.getUpdatedAt())
                .build();
    }

    @Transactional(readOnly = true)
    public List<LeadOperatorResponse> getOperators() {
        return userRepository.findByRoleInAndIsActiveTrueOrderByFirstNameAscLastNameAsc(
                        LeadAccessService.operatorRoles())
                .stream()
                .map(user -> LeadOperatorResponse.builder()
                        .id(user.getId())
                        .fullName(formatUserName(user))
                        .build())
                .collect(Collectors.toList());
    }

    @Transactional
    @Audited(action = AuditAction.CREATE, entity = "Student",
        summary = "'Lead o''quvchiga aylantirildi'",
        entityId = "#result.id")
    public LeadConvertResponse convertToStudent(Long id, LeadConvertRequest request) {
        Lead lead = leadRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Lead", id));
        leadAccessService.assertCanAccessLead(lead);

        if (Boolean.TRUE.equals(lead.getConverted())
                || lead.getStatus() == LeadStatus.CONVERTED
                || lead.getStudent() != null) {
            throw new BadRequestException("Bu lid allaqachon o'quvchiga aylantirilgan");
        }

        LeadConvertRequest body = request != null ? request : new LeadConvertRequest();

        if (studentRepository.findByPhone(lead.getPhone()).isPresent()) {
            throw new DuplicateResourceException(
                "Student with phone already exists: " + lead.getPhone());
        }

        String fullName = lead.getFullName() != null ? lead.getFullName().trim() : "";
        String firstName = fullName.isEmpty() ? "" : fullName.split("\\s+")[0];
        String lastName = fullName.contains(" ")
            ? fullName.substring(fullName.indexOf(' ') + 1).trim() : "";

        Student student = Student.builder()
                .firstName(firstName)
                .lastName(lastName.isBlank() ? "-" : lastName)
                .phone(lead.getPhone())
                .address(lead.getAddress())
                .notes(lead.getNotes())
                .status(StudentStatus.ACTIVE)
                .admissionDate(LocalDate.now())
                .admissionNumber(studentService.generateNextAdmissionNumber())
                .convertedFromLeadId(lead.getId())
                .marketingSource(parseMarketingSource(lead.getSource()))
                .paymentStatus(PaymentStatus.PENDING)
                .build();
        student = studentRepository.save(student);

        User converter = getCurrentUser();
        User attributed = lead.getAssignedUser() != null ? lead.getAssignedUser() : converter;
        if (converter != null) {
            student.setCreatedBy(converter);
        }
        if (attributed != null) {
            student.setAttributedUserId(attributed.getId());
        } else if (converter != null) {
            student.setAttributedUserId(converter.getId());
        }
        student = studentRepository.save(student);

        studentService.syncParentFromPhone(student, lead.getParentPhone(), lead.getAddress());

        if (body.getGroupId() != null) {
            StudentGroupRequest groupRequest = new StudentGroupRequest();
            groupRequest.setStudentId(student.getId());
            groupRequest.setGroupId(body.getGroupId());
            groupRequest.setJoinDate(LocalDate.now());
            groupRequest.setPaymentStartDate(
                body.getPaymentStartDate() != null ? body.getPaymentStartDate() : LocalDate.now());
            groupRequest.setMonthlyFee(body.getMonthlyFee());
            groupRequest.setPaymentType(body.getPaymentType());
            groupRequest.setLessonPrice(body.getLessonPrice());
            groupRequest.setIsTrial(body.getIsTrial());
            groupService.addStudentToGroup(groupRequest);
        }

        Long studentId = student.getId();
        student = studentRepository.findById(studentId)
            .orElseThrow(() -> new ResourceNotFoundException("Student", studentId));

        LeadStatus statusBeforeConvert = lead.getStatus();
        lead.setStudent(student);
        lead.setConverted(true);
        lead.setStatus(LeadStatus.CONVERTED);
        leadRepository.save(lead);

        if (statusBeforeConvert != LeadStatus.CONVERTED) {
            recordStatusChange(lead, statusBeforeConvert, LeadStatus.CONVERTED,
                "O'quvchiga aylantirildi");
        }

        return LeadConvertResponse.builder()
            .id(student.getId())
            .admissionNumber(student.getAdmissionNumber())
            .paymentStatus(student.getPaymentStatus())
            .nextPaymentDate(student.getNextPaymentDate())
            .leadId(lead.getId())
            .build();
    }

    /**
     * Kanban sarlavhalari uchun hisoblagichlar — bitta GROUP BY so'rov.
     *
     * <p>Bosqichlar avval nol bilan to'ldiriladi, keyin so'rov natijasi
     * ustiga yoziladi: bo'sh ustun ham javobda qoladi va ro'yxat enum
     * tartibida, ya'ni kanban ustunlari tartibida keladi.
     *
     * <p>SALES_MANAGER uchun doira {@code LeadAccessService} dan olinadi —
     * u faqat o'ziga biriktirilgan lidlarni sanaydi va unda
     * "biriktirilmagan" ustuni tabiiy ravishda nol bo'ladi.
     *
     * <p>{@code totalAmount} null: {@code Lead} da budjet maydoni yo'q.
     */
    @Transactional(readOnly = true)
    public LeadKanbanStatsResponse getKanbanStats() {
        Optional<Long> scope = leadAccessService.resolveOperatorScope();
        List<Object[]> rows = scope
                .map(leadRepository::countKanbanGroupedByUser)
                .orElseGet(leadRepository::countKanbanGrouped);

        Map<LeadStatus, Long> counts = new EnumMap<>(LeadStatus.class);
        for (LeadStatus status : LeadStatus.values()) {
            counts.put(status, 0L);
        }

        long unassigned = 0L;
        for (Object[] row : rows) {
            LeadStatus status = row[0] instanceof LeadStatus s
                    ? s
                    : LeadStatus.fromString(row[0] != null ? row[0].toString() : null);
            counts.merge(status, toCount(row[1]), Long::sum);
            unassigned += toCount(row[2]);
        }

        List<LeadKanbanColumnDto> columns = new java.util.ArrayList<>(counts.size());
        counts.forEach((status, count) -> columns.add(LeadKanbanColumnDto.builder()
                .status(status)
                .count(count)
                .totalAmount(null)
                .build()));

        return LeadKanbanStatsResponse.builder()
                .columns(columns)
                .unassigned(LeadKanbanColumnDto.builder()
                        .count(unassigned)
                        .totalAmount(null)
                        .build())
                .build();
    }

    private static long toCount(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    @Transactional(readOnly = true)
    public LeadStatsResponse getStats() {
        Map<LeadStatus, Long> byStatus = new EnumMap<>(LeadStatus.class);
        for (LeadStatus status : LeadStatus.values()) {
            byStatus.put(status, 0L);
        }
        leadRepository.countByStatusGrouped().forEach(row -> {
            LeadStatus status = row[0] instanceof LeadStatus
                    ? (LeadStatus) row[0]
                    : LeadStatus.fromString(row[0] != null ? row[0].toString() : null);
            long count = row[1] instanceof Number n ? n.longValue() : 0L;
            byStatus.merge(status, count, Long::sum);
        });

        long convertedByStatus = leadRepository.countByStatus(LeadStatus.CONVERTED);
        long convertedByFlag = leadRepository.countByConvertedTrue();
        // Yagona manba: status=CONVERTED (convert oqimi ikkalasini ham yozadi)
        long converted = convertedByStatus;
        byStatus.put(LeadStatus.CONVERTED, converted);

        long newCount = byStatus.getOrDefault(LeadStatus.NEW, 0L);
        long rejected = byStatus.getOrDefault(LeadStatus.REJECTED, 0L);

        log.info("Lead stats: CONVERTED(status)={}, converted(flag)={}, NEW={}, REJECTED={}, total={}",
            convertedByStatus, convertedByFlag, newCount, rejected, leadRepository.count());
        if (convertedByStatus != convertedByFlag) {
            log.warn("Lead converted mismatch: status={} vs flag={} — convert oqimi ikkalasini sync qilishi kerak",
                convertedByStatus, convertedByFlag);
        }

        List<LeadOperatorStatsResponse> byOperator = leadRepository.countByOperatorGrouped().stream()
                .map(row -> LeadOperatorStatsResponse.builder()
                        .userId((Long) row[0])
                        .name(row[1] != null ? row[1].toString().trim() : "")
                        .count((Long) row[2])
                        .converted(row[3] != null ? ((Number) row[3]).longValue() : 0L)
                        .build())
                .collect(Collectors.toList());

        return LeadStatsResponse.builder()
                .total(leadRepository.count())
                .newCount(newCount)
                .converted(converted)
                .rejected(rejected)
                .byStatus(byStatus)
                .byOperator(byOperator)
                .unassigned(leadRepository.countByAssignedUserIsNull())
                .build();
    }

    /**
     * Eski status nomlarini tirik qiymatlarga ko'chiradi
     * ({@code POST /api/admin/repair/lead-statuses}).
     *
     * <p>DIQQAT: faqat {@code leads} jadvalini o'zgartiradi.
     * {@code lead_comments.status_at_comment} va
     * {@code lead_status_history} ustunlari bu yerda qamralmaydi —
     * ular uchun {@code db/migration/V44__lead_status_contacted.sql}.
     */
    @Transactional
    public Map<String, Object> migrateLeadStatuses() {
        Map<String, Integer> migrated = new LinkedHashMap<>();
        migrated.put("DAY_1_WORKED", leadRepository.migrateStatus("DAY_1_WORKED", "CONTACTED"));
        migrated.put("DAY_2_WORKED", leadRepository.migrateStatus("DAY_2_WORKED", "CONTACTED"));
        migrated.put("DAY_3_WORKED", leadRepository.migrateStatus("DAY_3_WORKED", "CONTACTED"));
        migrated.put("DAY_4_WORKED", leadRepository.migrateStatus("DAY_4_WORKED", "CONTACTED"));
        migrated.put("IN_PROGRESS", leadRepository.migrateStatus("IN_PROGRESS", "CONTACTED"));
        migrated.put("INTERESTED", leadRepository.migrateStatus("INTERESTED", "CONTACTED"));
        migrated.put("ENROLLED_CONVERTED", leadRepository.migrateEnrolledConverted());
        migrated.put("ENROLLED_ONLINE", leadRepository.migrateEnrolledOnline());
        migrated.put("ENROLLED_OFFLINE", leadRepository.migrateEnrolledOffline());

        Map<String, Object> result = new HashMap<>();
        result.put("migrated", migrated);
        result.put("totalUpdated", migrated.values().stream().mapToInt(Integer::intValue).sum());
        return result;
    }

    private Specification<Lead> buildLeadSpec(
            String status, String search, Long assignedUserId, Boolean unassigned,
            String fromDate, String toDate) {
        Specification<Lead> spec = Specification.where(null);

        if (status != null && !status.isBlank()) {
            if (status.contains(",")) {
                List<LeadStatus> statuses = Arrays.stream(status.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(this::parseStatus)
                        .toList();
                spec = spec.and((root, query, cb) -> root.get("status").in(statuses));
            } else {
                LeadStatus leadStatus = parseStatus(status);
                spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), leadStatus));
            }
        }
        if (assignedUserId != null) {
            spec = spec.and((root, query, cb) ->
                    cb.equal(root.get("assignedUser").get("id"), assignedUserId));
        }
        if (Boolean.TRUE.equals(unassigned)) {
            spec = spec.and((root, query, cb) -> cb.isNull(root.get("assignedUser")));
        }
        if (search != null && !search.isBlank()) {
            String term = "%" + search.trim().toLowerCase() + "%";
            String phoneTerm = "%" + search.trim() + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("fullName")), term),
                    cb.like(root.get("phone"), phoneTerm)));
        }
        if (fromDate != null && !fromDate.isBlank()) {
            LocalDate start = parseDateParam(fromDate, "fromDate");
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(
                    root.get("createdAt"), start.atStartOfDay()));
        }
        if (toDate != null && !toDate.isBlank()) {
            LocalDate end = parseDateParam(toDate, "toDate");
            LocalDateTime exclusiveEnd = end.plusDays(1).atStartOfDay();
            spec = spec.and((root, query, cb) -> cb.lessThan(
                    root.get("createdAt"), exclusiveEnd));
        }
        return spec;
    }

    private LocalDate parseDateParam(String value, String paramName) {
        try {
            return LocalDate.parse(value.trim());
        } catch (Exception e) {
            throw new BadRequestException("Noto'g'ri " + paramName + " formati (yyyy-MM-dd): " + value);
        }
    }

    private LeadStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            throw new BadRequestException("Status majburiy");
        }
        try {
            return LeadStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Noto'g'ri lead status: " + status);
        }
    }

    /**
     * Bosqich o'tishini tarixga yozadi. Muallif aniqlanmasa ham yozuv
     * yaratiladi — {@code changedBy} null bo'ladi (masalan kelajakdagi
     * avtomatik o'tishlar uchun), chunki o'tish fakti muallifdan muhimroq.
     */
    private void recordStatusChange(Lead lead, LeadStatus from, LeadStatus to, String note) {
        leadStatusHistoryRepository.save(LeadStatusHistory.builder()
                .lead(lead)
                .fromStatus(from)
                .toStatus(to)
                .changedBy(getCurrentUser())
                .note(note)
                .build());
    }

    private void addSystemComment(Lead lead, String text) {
        User author = getCurrentUser();
        if (author == null) {
            return;
        }
        LeadComment comment = LeadComment.builder()
                .lead(lead)
                .author(author)
                .text(text)
                .statusAtComment(lead.getStatus())
                .build();
        leadCommentRepository.save(comment);
    }

    /**
     * Joriy foydalanuvchi yoki null. Null bo'lishi mumkin: {@code /api/leads/public}
     * autentifikatsiyasiz chaqiriladi.
     */
    private User getCurrentUser() {
        return leadAccessService.currentUserOrNull();
    }

    private MarketingSource parseMarketingSource(String src) {
        if (src == null || src.isBlank()) {
            return MarketingSource.OTHER;
        }
        try {
            return MarketingSource.valueOf(src.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return MarketingSource.OTHER;
        }
    }

    private PageResponse<LeadResponse> toPageResponse(Page<Lead> leads) {
        List<Long> leadIds = leads.getContent().stream()
                .map(Lead::getId)
                .collect(Collectors.toList());
        Map<Long, Long> commentCounts = loadCommentCounts(leadIds);
        Map<Long, String> lastComments = loadLastCommentTexts(leadIds);
        // Sahifadagi barcha lidlarning eng yaqin ochiq vazifasi — bitta so'rov.
        // Denormalizatsiya (leads.next_task_due_at) ataylab qilinmadi:
        // converted/status juftligi allaqachon sinxrondan chiqib ketgan.
        Map<Long, Task> nextTasks = taskService.loadNextOpenTasks(leadIds);
        LocalDateTime now = LocalDateTime.now();

        return PageResponse.<LeadResponse>builder()
                .content(leads.getContent().stream()
                        .map(lead -> toResponse(
                                lead,
                                commentCounts.getOrDefault(lead.getId(), 0L),
                                lastComments.get(lead.getId()),
                                nextTasks.get(lead.getId()),
                                now))
                        .collect(Collectors.toList()))
                .pageNumber(leads.getNumber())
                .pageSize(leads.getSize())
                .totalElements(leads.getTotalElements())
                .totalPages(leads.getTotalPages())
                .last(leads.isLast())
                .build();
    }

    private Map<Long, Long> loadCommentCounts(List<Long> leadIds) {
        if (leadIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> counts = new HashMap<>();
        leadCommentRepository.countByLeadIds(leadIds).forEach(row ->
                counts.put((Long) row[0], (Long) row[1]));
        return counts;
    }

    private Map<Long, String> loadLastCommentTexts(List<Long> leadIds) {
        if (leadIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> texts = new HashMap<>();
        leadCommentRepository.findLatestTextByLeadIds(leadIds).forEach(row ->
                texts.put(((Number) row[0]).longValue(), row[1] != null ? row[1].toString() : null));
        return texts;
    }

    private LeadResponse toResponse(Lead lead) {
        long commentsCount = leadCommentRepository.countByLeadId(lead.getId());
        String lastCommentText = null;
        List<LeadComment> latest = leadCommentRepository.findByLeadIdOrderByCreatedAtDesc(lead.getId());
        if (!latest.isEmpty()) {
            lastCommentText = latest.get(0).getText();
        }
        Task nextTask = taskService.loadNextOpenTasks(List.of(lead.getId())).get(lead.getId());
        return toResponse(lead, commentsCount, lastCommentText, nextTask, LocalDateTime.now());
    }

    private LeadResponse toResponse(
            Lead lead, long commentsCount, String lastCommentText,
            Task nextTask, LocalDateTime now) {
        String studentName = null;
        if (lead.getStudent() != null) {
            studentName = (lead.getStudent().getFirstName() != null ? lead.getStudent().getFirstName() : "")
                    + " "
                    + (lead.getStudent().getLastName() != null ? lead.getStudent().getLastName() : "");
            studentName = studentName.trim();
        }
        return LeadResponse.builder()
                .id(lead.getId())
                .uuid(lead.getUuid())
                .fullName(lead.getFullName())
                .phone(lead.getPhone())
                .parentPhone(lead.getParentPhone())
                .address(lead.getAddress())
                .course(lead.getCourse())
                .format(lead.getFormat())
                .status(lead.getStatus())
                .source(lead.getSource())
                .notes(lead.getNotes())
                .converted(lead.getConverted())
                .studentId(lead.getStudent() != null ? lead.getStudent().getId() : null)
                .studentName(studentName != null && !studentName.isEmpty() ? studentName : null)
                .assignedUserId(lead.getAssignedUser() != null ? lead.getAssignedUser().getId() : null)
                .assignedUserName(lead.getAssignedUser() != null
                        ? formatUserName(lead.getAssignedUser()) : null)
                .assignedAt(lead.getAssignedAt())
                .commentsCount(commentsCount)
                .lastCommentText(lastCommentText)
                .nextTaskDueAt(nextTask != null ? nextTask.getDueAt() : null)
                .nextTaskTitle(nextTask != null ? nextTask.getTitle() : null)
                .taskState(nextTask != null
                        ? LeadTaskState.resolve(nextTask.getDueAt(), now)
                        : LeadTaskState.NONE)
                .createdAt(lead.getCreatedAt())
                .updatedAt(lead.getUpdatedAt())
                .build();
    }

    private LeadCommentResponse toCommentResponse(LeadComment comment) {
        return LeadCommentResponse.builder()
                .id(comment.getId())
                .leadId(comment.getLead().getId())
                .authorId(comment.getAuthor().getId())
                .authorFullName(formatUserName(comment.getAuthor()))
                .text(comment.getText())
                .statusAtComment(comment.getStatusAtComment())
                .createdAt(comment.getCreatedAt())
                .build();
    }

    private String formatUserName(User user) {
        return ((user.getFirstName() != null ? user.getFirstName() : "")
                + " "
                + (user.getLastName() != null ? user.getLastName() : "")).trim();
    }

    @Transactional(readOnly = true)
    public byte[] exportLeadsXlsx(String fromDate, String toDate, String status, Long operatorId) {
        Specification<Lead> spec = buildLeadSpec(status, null, operatorId, null, fromDate, toDate);
        List<Lead> leads = leadRepository.findAll(spec, Sort.by("createdAt").descending());

        List<Long> leadIds = leads.stream().map(Lead::getId).toList();
        Map<Long, Long> commentCounts = loadCommentCounts(leadIds);

        try (Workbook workbook = new XSSFWorkbook();
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet("Lidlar");

            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());

            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setBorderBottom(BorderStyle.THIN);
            headerStyle.setBorderTop(BorderStyle.THIN);
            headerStyle.setBorderLeft(BorderStyle.THIN);
            headerStyle.setBorderRight(BorderStyle.THIN);

            CellStyle bodyStyle = workbook.createCellStyle();
            bodyStyle.setBorderBottom(BorderStyle.THIN);
            bodyStyle.setBorderTop(BorderStyle.THIN);
            bodyStyle.setBorderLeft(BorderStyle.THIN);
            bodyStyle.setBorderRight(BorderStyle.THIN);

            CellStyle centerStyle = workbook.createCellStyle();
            centerStyle.cloneStyleFrom(bodyStyle);
            centerStyle.setAlignment(HorizontalAlignment.CENTER);

            String[] headers = {"№", "Ism", "Telefon", "Manba", "Operator", "Holat", "Izohlar soni", "Sana"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            java.time.format.DateTimeFormatter dateFormatter = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy");

            int rowIdx = 1;
            for (Lead lead : leads) {
                Row row = sheet.createRow(rowIdx);

                Cell cell0 = row.createCell(0);
                cell0.setCellValue(rowIdx);
                cell0.setCellStyle(centerStyle);

                Cell cell1 = row.createCell(1);
                cell1.setCellValue(lead.getFullName() != null ? lead.getFullName() : "");
                cell1.setCellStyle(bodyStyle);

                Cell cell2 = row.createCell(2);
                cell2.setCellValue(lead.getPhone() != null ? lead.getPhone() : "");
                cell2.setCellStyle(bodyStyle);

                Cell cell3 = row.createCell(3);
                cell3.setCellValue(translateSource(lead.getSource()));
                cell3.setCellStyle(bodyStyle);

                Cell cell4 = row.createCell(4);
                cell4.setCellValue(lead.getAssignedUser() != null ? formatUserName(lead.getAssignedUser()) : "-");
                cell4.setCellStyle(bodyStyle);

                Cell cell5 = row.createCell(5);
                cell5.setCellValue(translateStatus(lead.getStatus()));
                cell5.setCellStyle(bodyStyle);

                Cell cell6 = row.createCell(6);
                cell6.setCellValue(commentCounts.getOrDefault(lead.getId(), 0L).intValue());
                cell6.setCellStyle(centerStyle);

                Cell cell7 = row.createCell(7);
                cell7.setCellValue(lead.getCreatedAt() != null ? lead.getCreatedAt().format(dateFormatter) : "");
                cell7.setCellStyle(centerStyle);

                rowIdx++;
            }

            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(out);
            return out.toByteArray();

        } catch (Exception e) {
            throw new RuntimeException("Excel fayl yaratishda xatolik", e);
        }
    }

    private String translateSource(String source) {
        if (source == null || source.isBlank()) {
            return "Boshqa";
        }
        return switch (source.trim().toUpperCase()) {
            case "INSTAGRAM" -> "Instagram";
            case "TELEGRAM" -> "Telegram";
            case "YOUTUBE" -> "YouTube";
            case "FACEBOOK" -> "Facebook";
            case "TARGET" -> "Target";
            case "SELF_CALL" -> "O'zi qo'ng'iroq";
            case "FORMER_STUDENT" -> "Avvalgi o'quvchi";
            case "REFERRAL" -> "Tavsiya";
            case "WALK_IN" -> "Kelib ko'rgan";
            case "OFFLINE" -> "Oflayn";
            case "LEAD" -> "Lid";
            case "WEBSITE" -> "Veb-sayt";
            case "OTHER" -> "Boshqa";
            default -> source;
        };
    }

    private String translateStatus(LeadStatus status) {
        if (status == null) {
            return "Yangi";
        }
        return switch (status) {
            case NEW -> "Yangi";
            case CONTACTED -> "Bog'lanildi";
            case ONLINE_ENROLLED -> "Online guruhga yozildi";
            case OFFLINE_ENROLLED -> "Offline guruhga yozildi";
            case ONLINE_PAID -> "Online to'ladi";
            case OFFLINE_PAID -> "Offline to'ladi";
            case CONVERTED -> "O'quvchiga aylandi";
            case REJECTED -> "Rad etildi";
        };
    }
}
