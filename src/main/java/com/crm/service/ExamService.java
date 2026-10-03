package com.crm.service;

import com.crm.audit.AuditAction;
import com.crm.audit.AuditContext;
import com.crm.billing.BillingLocks;
import com.crm.billing.BillingStatusService;
import com.crm.audit.Audited;
import com.crm.billing.ReceiptNumberService;
import com.crm.dto.request.ExamRegistrationRequest;
import com.crm.dto.request.ExamRequest;
import com.crm.dto.request.ExamResultRequest;
import com.crm.dto.response.*;
import com.crm.entity.*;
import com.crm.entity.enums.ExamPaymentStatus;
import com.crm.entity.enums.ExamRegistrationStatus;
import com.crm.entity.enums.StudentStatus;
import com.crm.exception.BadRequestException;
import com.crm.exception.CodedException;
import com.crm.exception.ConflictException;
import com.crm.exception.DuplicateResourceException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Imtihonlar, natijalar va imtihonga yozilish.
 *
 * <p><b>Imtihon to'lovi</b> (leaves-exams-contracts §4): narx — {@code exams.fee} (0 = bepul).
 * Pullik imtihonga yozilish — bitta tranzaksiyada kassaga kirim ({@code cash_transactions.exam_registration_id}),
 * bekor qilish — kassaga REVERSAL. O'quvchi balansi (billing-v2 ledger) ishlatilmaydi: imtihon to'lovi
 * o'qish uchun to'lov emas va qarzni yopmaydi. Qulf tartibi billing-v2 §7.2: Student → CashRegister.
 */
@Service
@RequiredArgsConstructor
public class ExamService {

    private static final int MIN_PRESENT_DAYS_FOR_EXAM = 8;
    private static final int IDEMPOTENCY_KEY_MAX = 64;

    /** {@code exam.notEligible} sababi ({@code data.reason}). */
    public static final String REASON_ATTENDANCE = "ATTENDANCE";
    public static final String REASON_NO_ENROLLMENT = "NO_ENROLLMENT";
    public static final String REASON_OVERDUE = "OVERDUE";

    private final ExamRepository examRepository;
    private final ExamResultRepository examResultRepository;
    private final ExamRegistrationRepository examRegistrationRepository;
    private final StudentRepository studentRepository;
    private final ClassRepository classRepository;
    private final GroupRepository groupRepository;
    private final SubjectRepository subjectRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final AttendanceRepository attendanceRepository;
    private final CashTransactionRepository cashTransactionRepository;
    private final TeacherAccessService teacherAccessService;
    private final UserRepository userRepository;
    private final BillingStatusService billingStatusService;
    private final CashRegisterService cashRegisterService;
    private final ReceiptNumberService receiptNumberService;
    private final BillingLocks locks;
    private final EntityManager entityManager;
    private final Clock billingClock;

    /**
     * {@code GET /api/exams} holati (hisoblanadi — ustun emas). Sana chegarasi {@code exam.closed} bilan bir xil:
     * bugungi va sanasiz imtihon — UPCOMING (yozilish ochiq).
     */
    public enum ListStatus {
        /** Faol (standart). */
        ACTIVE,
        /** Faol, sanasi bugun yoki keyin, yoki sanasiz. */
        UPCOMING,
        /** Faol, sanasi o'tgan. */
        PAST,
        /** O'chirilgan (soft delete). */
        INACTIVE;

        public static ListStatus parse(String s) {
            if (s == null || s.isBlank()) {
                return ACTIVE;
            }
            try {
                return valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw CodedException.badRequest("exam.status.invalid", s);
            }
        }
    }

    /** {@code GET /api/exams} filtri — {@code from}/{@code to} imtihon sanasi bo'yicha (ikkalasi kiritilgan). */
    public record ExamFilter(Long groupId, LocalDate from, LocalDate to, ListStatus status) {
    }

    /** TEACHER — faqat o'z imtihonlari (o'zi yoki guruhi orqali); SA/A/ACC — hammasi. */
    @Transactional(readOnly = true)
    public PageResponse<ExamResponse> getAllExams(ExamFilter filter, int page, int size) {
        if (filter.from() != null && filter.to() != null && filter.to().isBefore(filter.from())) {
            throw CodedException.badRequest("exam.dates.invalid");
        }
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Long teacherId = teacherAccessService.resolveTeacherScope().map(Teacher::getId).orElse(null);
        Page<Exam> p = examRepository.findAll(examSpec(filter, teacherId, LocalDate.now(billingClock)), pageable);
        return PageResponse.<ExamResponse>builder()
            .content(p.getContent().stream().map(this::toExamResponse).collect(Collectors.toList()))
            .pageNumber(page).pageSize(size)
            .totalElements(p.getTotalElements()).totalPages(p.getTotalPages()).last(p.isLast())
            .build();
    }

    @Transactional(readOnly = true)
    public ExamResponse getExamById(Long id) {
        Exam exam = findExamById(id);
        assertExamAccess(exam);
        return toExamResponse(exam);
    }

    @Transactional
    public ExamResponse createExam(ExamRequest request) {
        if (request.getGroupId() != null) {
            teacherAccessService.assertOwnsGroup(request.getGroupId());
        }
        Exam exam = buildExam(new Exam(), request);
        if (exam.getFee() == null) {
            exam.setFee(BigDecimal.ZERO);
        }
        if (teacherAccessService.isCurrentUserTeacher()) {
            exam.setTeacher(teacherAccessService.getCurrentTeacherOrThrow());
        }
        exam.setIsActive(true);
        Exam saved = examRepository.save(exam);
        // Pullik imtihonga to'lovsiz yozilib bo'lmaydi (D9) — avtomatik yozish faqat bepulda
        if (saved.getGroup() != null && saved.getFee().signum() == 0) {
            autoRegisterGroupStudents(saved);
        }
        return toExamResponse(saved);
    }

    /** {@code fee} REGISTERED yozilish bor imtihonda o'zgarmaydi — 409 {@code exam.feeLocked}. */
    @Transactional
    public ExamResponse updateExam(Long id, ExamRequest request) {
        Exam exam = findExamById(id);
        assertExamAccess(exam);
        if (request.getGroupId() != null) {
            teacherAccessService.assertOwnsGroup(request.getGroupId());
        }
        if (request.getFee() != null && nz(exam.getFee()).compareTo(request.getFee()) != 0
                && examRegistrationRepository.existsByExamIdAndStatus(id, ExamRegistrationStatus.REGISTERED)) {
            throw new ConflictException("exam.feeLocked");
        }
        buildExam(exam, request);
        return toExamResponse(examRepository.save(exam));
    }

    /**
     * Soft delete. To'langan REGISTERED yozilish bo'lsa — 409 {@code exam.hasRegistrations}: avval
     * yozilishlar bekor qilinadi (pul qaytishi aniq bo'lsin, §4.3).
     */
    @Transactional
    public void deleteExam(Long id) {
        Exam exam = findExamById(id);
        assertExamAccess(exam);
        if (examRegistrationRepository.existsByExamIdAndStatusAndFeeStatus(
                id, ExamRegistrationStatus.REGISTERED, ExamPaymentStatus.PAID)) {
            throw new ConflictException("exam.hasRegistrations");
        }
        exam.setIsActive(false);
        examRepository.save(exam);
    }

    @Transactional(readOnly = true)
    public List<ExamResultResponse> getResultsByExam(Long examId) {
        Exam exam = findExamById(examId);
        assertExamAccess(exam);

        Map<Long, ExamResult> byStudent = examResultRepository.findByExamId(examId).stream()
            .collect(Collectors.toMap(r -> r.getStudent().getId(), r -> r, (a, b) -> a, LinkedHashMap::new));

        List<ExamResultResponse> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();

        for (ExamRegistration reg : examRegistrationRepository.findByExamId(examId)) {
            if (reg.getStatus() == ExamRegistrationStatus.CANCELLED) {
                continue;
            }
            Long studentId = reg.getStudent().getId();
            if (!seen.add(studentId)) {
                continue;
            }
            ExamResult existing = byStudent.get(studentId);
            if (existing != null) {
                out.add(toResultResponse(existing));
            } else {
                out.add(toEmptyResultResponse(exam, reg.getStudent()));
            }
        }

        for (ExamResult r : byStudent.values()) {
            if (!seen.contains(r.getStudent().getId())) {
                out.add(toResultResponse(r));
            }
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<ExamResultResponse> getResultsByStudent(Long studentId) {
        teacherAccessService.assertOwnsStudent(studentId);
        return examResultRepository.findByStudentId(studentId).stream()
            .map(this::toResultResponse).collect(Collectors.toList());
    }

    @Transactional
    public ExamResultResponse addResult(Long examId, ExamResultRequest request) {
        Exam exam = findExamById(examId);
        assertExamAccess(exam);

        if (request.getStudentId() == null) {
            throw new BadRequestException("Student ID is required");
        }
        // TEACHER — faqat o'z guruhlaridagi o'quvchiga natija yozadi (E-02).
        teacherAccessService.assertOwnsStudent(request.getStudentId());

        if (examResultRepository.findByExamIdAndStudentId(examId, request.getStudentId()).isPresent()) {
            throw new DuplicateResourceException("Result for this student already exists in exam");
        }

        Student student = studentRepository.findById(request.getStudentId())
            .orElseThrow(() -> new ResourceNotFoundException("Student", request.getStudentId()));

        BigDecimal score = request.resolveScore();
        ExamResult result = ExamResult.builder()
            .exam(exam).student(student)
            .marksObtained(score)
            .grade(request.getGrade())
            .remarks(request.resolveNotes())
            .isPassed(computePassed(score, exam.getPassMarks()))
            .build();

        return toResultResponse(examResultRepository.save(result));
    }

    @Transactional
    @Audited(action = AuditAction.UPDATE, entity = "ExamResult",
        summary = "'Imtihon natijasi tahrirlandi'",
        entityId = "#resultId")
    public ExamResultResponse updateResult(Long examId, Long resultId, ExamResultRequest request) {
        Exam exam = findExamById(examId);
        assertExamAccess(exam);

        ExamResult result = examResultRepository.findById(resultId)
            .orElseThrow(() -> new ResourceNotFoundException("ExamResult", resultId));
        if (!exam.getId().equals(result.getExam().getId())) {
            throw new BadRequestException("Natija ushbu imtihonga tegishli emas");
        }

        String editNote = request.getEditNote();
        if (editNote == null || editNote.isBlank()) {
            throw new BadRequestException("O'zgartirish sababi kiritilishi shart");
        }

        BigDecimal oldScore = result.getMarksObtained();
        BigDecimal newScore = request.resolveScore() != null ? request.resolveScore() : oldScore;

        String changeLog = formatScoreChange(oldScore, newScore) + ", sabab: " + editNote.trim();

        result.setMarksObtained(newScore);
        if (request.getGrade() != null) {
            result.setGrade(request.getGrade());
        }
        if (request.resolveNotes() != null) {
            result.setRemarks(request.resolveNotes());
        }
        result.setIsPassed(computePassed(newScore, exam.getPassMarks()));
        result.setEditNote(changeLog);
        result.setEditedAt(LocalDateTime.now());
        result.setEditedBy(currentUserOrNull());

        return toResultResponse(examResultRepository.save(result));
    }

    /** Backward-compat: examId siz yangilash */
    @Transactional
    public ExamResultResponse updateResult(Long resultId, ExamResultRequest request) {
        ExamResult result = examResultRepository.findById(resultId)
            .orElseThrow(() -> new ResourceNotFoundException("ExamResult", resultId));
        return updateResult(result.getExam().getId(), resultId, request);
    }

    /**
     * @deprecated Eski preview (billing davrlaridan "to'lanmagan kunlar" hisobi, E-08) olib tashlandi —
     * summa endi {@code exams.fee}. Endpoint bir bosqich qoladi: ruxsat tekshiruvi o'sha (TEACHER — faqat
     * o'z imtihoni va o'z o'quvchisi), javobda imtihon narxi.
     */
    @Deprecated
    @Transactional(readOnly = true)
    public Map<String, Object> calculateExamPaymentPreview(Long examId, Long studentId) {
        Exam exam = findExamById(examId);
        assertExamAccess(exam);
        studentRepository.findById(studentId)
            .orElseThrow(() -> new ResourceNotFoundException("Student", studentId));
        teacherAccessService.assertOwnsStudent(studentId);

        BigDecimal fee = nz(exam.getFee());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("examId", exam.getId());
        result.put("examDate", exam.getExamDate());
        result.put("examName", exam.getExamName());
        result.put("fee", fee);
        result.put("amountDue", fee);
        result.put("free", fee.signum() == 0);
        result.put("message", fee.signum() == 0 ? "Bepul imtihon"
            : "Imtihon narxi: " + ContractService.formatAmount(fee) + " so'm (yozilishda kassaga)");
        return result;
    }

    /**
     * Imtihonga kira oladigan o'quvchilar (E-02):
     * <ul>
     *   <li>nomzodlar — imtihon guruhining faol o'quvchilari; guruhsiz imtihonda
     *       TEACHER uchun o'z guruhlari o'quvchilari, ma'muriyat uchun hammasi
     *       (avval {@code findAll()} — har qanday TEACHER ga barcha o'quvchilar);</li>
     *   <li>shaxsiy ma'lumot rol bo'yicha: TEACHER telefon, ota-ona telefoni,
     *       manzil, tug'ilgan sana va izohlarni olmaydi.</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public List<StudentResponse> getEligibleStudents(Long examId) {
        Exam exam = findExamById(examId);
        assertExamAccess(exam);
        Optional<Teacher> teacherScope = teacherAccessService.resolveTeacherScope();

        Collection<Student> candidates;
        if (exam.getGroup() != null) {
            Map<Long, Student> byId = new LinkedHashMap<>();
            for (StudentGroup sg : studentGroupRepository.findActiveByGroupId(exam.getGroup().getId())) {
                byId.putIfAbsent(sg.getStudent().getId(), sg.getStudent());
            }
            candidates = byId.values();
        } else if (teacherScope.isPresent()) {
            candidates = studentRepository
                .findDistinctActiveByTeacherId(teacherScope.get().getId(), Pageable.unpaged())
                .getContent();
        } else {
            candidates = studentRepository.findAll();
        }

        boolean fullProfile = teacherScope.isEmpty();
        return candidates.stream()
            .filter(s -> ineligibleReason(exam, s.getId()).isEmpty())
            .map(s -> fullProfile ? toStudentResponse(s) : toLimitedStudentResponse(s))
            .collect(Collectors.toList());
    }

    /**
     * Imtihonga kirish sharti (E-01); bajarilmasa — sabab ({@code exam.notEligible}, {@code data.reason}):
     * <ol>
     *   <li>{@code ATTENDANCE} — kamida {@value #MIN_PRESENT_DAYS_FOR_EXAM} marta PRESENT emas;</li>
     *   <li>{@code NO_ENROLLMENT} — hisob yuritiladigan faol yozilma yo'q (trial/muzlatilgan emas) —
     *       guruhli imtihonda aynan shu guruhdagi;</li>
     *   <li>{@code OVERDUE} — yozilmalardan biri billing-v2 bo'yicha OVERDUE (D8). Holat bugungi sana
     *       bo'yicha ({@link BillingStatusService#isOverdue}) — saqlangan snapshot eskirgan bo'lsa ham to'g'ri.</li>
     * </ol>
     */
    Optional<String> ineligibleReason(Exam exam, Long studentId) {
        long presentDays = attendanceRepository.countPresentDaysForStudent(studentId);
        if (presentDays < MIN_PRESENT_DAYS_FOR_EXAM) {
            return Optional.of(REASON_ATTENDANCE);
        }
        List<StudentGroup> billed = billedEnrollments(exam, studentId);
        if (billed.isEmpty()) {
            return Optional.of(REASON_NO_ENROLLMENT);
        }
        return isOverdue(billed) ? Optional.of(REASON_OVERDUE) : Optional.empty();
    }

    private List<StudentGroup> billedEnrollments(Exam exam, Long studentId) {
        Long examGroupId = exam.getGroup() != null ? exam.getGroup().getId() : null;
        return studentGroupRepository.findActiveByStudentId(studentId).stream()
            .filter(BillingStatusService::isBillingOpen)
            .filter(sg -> examGroupId == null || examGroupId.equals(sg.getGroup().getId()))
            .toList();
    }

    private boolean isOverdue(List<StudentGroup> enrollments) {
        LocalDate today = billingStatusService.today();
        return enrollments.stream().anyMatch(sg -> billingStatusService.isOverdue(sg, today));
    }

    // ── Yozilish (§4.2) ─────────────────────────────────────────────────

    /**
     * @deprecated {@code POST /api/exams/{id}/registrations}. Faqat bepul imtihonda ishlaydi — pullikda
     * 400 {@code exam.paymentRequired} (to'lovni faqat kassa bilan yangi endpoint qabul qiladi).
     */
    @Deprecated
    @Transactional
    public ExamRegistrationResponse registerStudentForExam(Long examId, Long studentId) {
        ExamRegistrationRequest request = new ExamRegistrationRequest();
        request.setStudentId(studentId);
        return register(examId, request, null);
    }

    /**
     * Yozilish — tekshiruvlar shu tartibda: imtihon ochiq (409 {@code exam.closed}) → TEACHER egaligi (403)
     * → kirish sharti (400 {@code exam.notEligible}) → dublikat (409 {@code exam.alreadyRegistered}) →
     * pullikda TEACHER (403 {@code exam.paymentRole}) → kassa va usul (400 {@code exam.paymentRequired}).
     *
     * <p>{@code fee > 0}: kassaga INCOME ({@code exam_registration_id}), chek raqami, {@code PAID}.
     * {@code fee = 0}: {@code FREE}, kassa yozuvi yo'q. {@code Idempotency-Key} takrori — o'sha yozilish
     * ({@code idempotentReplay = true}), ikkinchi kirim yo'q; boshqa imtihon/o'quvchi bilan — 409.
     */
    @Transactional
    @Audited(action = AuditAction.PAYMENT, entity = "ExamRegistration",
        summary = "'Imtihonga yozildi: ' + #result.studentName + ' — ' + #result.examName"
            + " + ' (' + #result.paymentStatus + ', ' + #result.amountPaid + ')'",
        entityId = "#result.id", label = "#result.studentName")
    public ExamRegistrationResponse register(Long examId, ExamRegistrationRequest request, String idempotencyKey) {
        String key = normalizeKey(idempotencyKey);
        if (key != null) {
            Optional<ExamRegistrationResponse> replay = replay(key, examId, request.getStudentId());
            if (replay.isPresent()) {
                return replay.get();
            }
        }
        Exam exam = findExamById(examId);
        LocalDate today = LocalDate.now(billingClock);
        if (!Boolean.TRUE.equals(exam.getIsActive())
                || (exam.getExamDate() != null && exam.getExamDate().isBefore(today))) {
            throw new ConflictException("exam.closed");
        }
        assertExamAccess(exam);
        Long studentId = request.getStudentId();
        if (studentId == null) {
            throw CodedException.badRequest("error.param.missing", "studentId");
        }
        Student student = studentRepository.findById(studentId)
            .orElseThrow(() -> new ResourceNotFoundException("Student", studentId));
        teacherAccessService.assertOwnsStudent(studentId);

        Optional<String> reason = ineligibleReason(exam, studentId);
        if (reason.isPresent()) {
            throw CodedException.badRequest("exam.notEligible", reason.get())
                .withData(Map.of("reason", reason.get()));
        }
        if (examRegistrationRepository.existsActive(examId, studentId)) {
            throw new ConflictException("exam.alreadyRegistered");
        }
        BigDecimal fee = nz(exam.getFee());
        boolean paid = fee.signum() > 0;
        if (paid && teacherAccessService.isCurrentUserTeacher()) {
            throw CodedException.forbidden("exam.paymentRole");
        }
        if (paid && (request.getCashRegisterId() == null || request.getPaymentMethod() == null)) {
            throw CodedException.badRequest("exam.paymentRequired", ContractService.formatAmount(fee));
        }

        // Qulf: Student → CashRegister (billing-v2 §7.2); qulf ostida takror va dublikat qayta tekshiriladi
        locks.acquire(BillingLocks.Plan.of().student(studentId).cashRegister(paid ? request.getCashRegisterId() : null));
        if (key != null) {
            Optional<ExamRegistrationResponse> replay = replay(key, examId, studentId);
            if (replay.isPresent()) {
                return replay.get();
            }
        }
        if (examRegistrationRepository.existsActive(examId, studentId)) {
            throw new ConflictException("exam.alreadyRegistered");
        }

        ExamRegistration reg = examRegistrationRepository.save(ExamRegistration.builder()
            .exam(exam)
            .student(student)
            .status(ExamRegistrationStatus.REGISTERED)
            .feeStatus(paid ? ExamPaymentStatus.PAID : ExamPaymentStatus.FREE)
            .amountDue(fee)
            .amountPaid(paid ? fee : BigDecimal.ZERO)
            .registrationDate(today)
            .idempotencyKey(key)
            .notes(request.getNote())
            .createdBy(currentUserOrNull())
            .build());
        if (paid) {
            String receipt = receiptNumberService.next();
            CashTransaction tx = cashRegisterService.recordIncome(
                request.getCashRegisterId(), fee, request.getPaymentMethod(), student,
                "Imtihon to'lovi: " + exam.getExamName(),
                "Chek " + receipt + " · imtihon #" + exam.getId(),
                today, request.getCashPart(), request.getCardPart(), null);
            tx.setExamRegistrationId(reg.getId());
            reg.setCashTransactionId(tx.getId());
            reg.setReceiptNumber(receipt);
        }
        return toRegistrationResponse(reg);
    }

    private Optional<ExamRegistrationResponse> replay(String key, Long examId, Long studentId) {
        return examRegistrationRepository.findByIdempotencyKey(key).map(existing -> {
            if (!existing.getExam().getId().equals(examId) || !existing.getStudent().getId().equals(studentId)) {
                throw new ConflictException("exam.idempotency.mismatch");
            }
            AuditContext.skip();  // takror — yangi amal emas
            ExamRegistrationResponse r = toRegistrationResponse(existing);
            r.setIdempotentReplay(true);
            return r;
        });
    }

    private static String normalizeKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String k = key.trim();
        if (k.length() > IDEMPOTENCY_KEY_MAX) {
            throw CodedException.badRequest("payroll.idempotency.keyTooLong", IDEMPOTENCY_KEY_MAX);
        }
        return k;
    }

    /** {@code GET /api/exams/{id}/registrations} — barcha holatlar, yangilari avval (E-04). */
    @Transactional(readOnly = true)
    public PageResponse<ExamRegistrationResponse> getRegistrations(Long examId, int page, int size) {
        Exam exam = findExamById(examId);
        assertExamAccess(exam);
        Page<ExamRegistration> p = examRegistrationRepository.findByExamId(examId,
            PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));
        return PageResponse.<ExamRegistrationResponse>builder()
            .content(p.getContent().stream().map(this::toRegistrationResponse).toList())
            .pageNumber(page).pageSize(size)
            .totalElements(p.getTotalElements()).totalPages(p.getTotalPages()).last(p.isLast())
            .build();
    }

    // ── Bekor qilish (§4.3) ─────────────────────────────────────────────

    /**
     * REGISTERED → CANCELLED (SA, A, ACC; sabab 3–500). Natija bor bo'lsa — 409
     * {@code exam.registration.hasResult}. PAID bo'lsa kassaga REVERSAL (asl yozuv o'chirilmaydi):
     * {@code REFUNDED}; chelak manfiyga tushsa — {@code negativeCashBalance = true}, rad etilmaydi.
     */
    @Transactional
    @Audited(action = AuditAction.PAYMENT_CANCEL, entity = "ExamRegistration",
        summary = "'Imtihon yozilishi bekor qilindi: ' + #result.studentName + ' — ' + #result.cancelReason",
        entityId = "#regId", label = "#result.studentName")
    public ExamRegistrationResponse cancelRegistration(Long examId, Long regId, String reason) {
        String why = reason != null ? reason.trim() : "";
        if (why.length() < 3 || why.length() > 500) {
            throw CodedException.badRequest("exam.registration.reasonRequired");
        }
        ExamRegistration reg = examRegistrationRepository.findById(regId)
            .filter(r -> r.getExam().getId().equals(examId))
            .orElseThrow(() -> CodedException.notFound("exam.registration.notFound", regId));
        CashTransaction original = reg.getCashTransactionId() != null
            ? cashTransactionRepository.findById(reg.getCashTransactionId()).orElse(null) : null;

        locks.acquire(BillingLocks.Plan.of()
            .student(reg.getStudent().getId())
            .cashRegister(original != null ? original.getCashRegister().getId() : null));
        entityManager.refresh(reg);
        if (reg.getStatus() != ExamRegistrationStatus.REGISTERED) {
            throw new ConflictException("exam.registration.notActive", reg.getStatus());
        }
        if (examResultRepository.findByExamIdAndStudentId(examId, reg.getStudent().getId()).isPresent()) {
            throw new ConflictException("exam.registration.hasResult");
        }
        boolean negative = false;
        if (reg.getFeeStatus() == ExamPaymentStatus.PAID && original != null) {
            CashRegisterService.ReversalResult reversal = cashRegisterService.recordReversal(original,
                "Imtihon yozilishi bekor qilindi — " + why);
            reg.setRefundCashTransactionId(reversal.transaction().getId());
            reg.setFeeStatus(ExamPaymentStatus.REFUNDED);
            negative = reversal.negativeBalance();
        }
        reg.setStatus(ExamRegistrationStatus.CANCELLED);
        reg.setCancelledAt(LocalDateTime.now(billingClock));
        reg.setCancelledBy(currentUserOrNull());
        reg.setCancelReason(why);
        ExamRegistrationResponse response = toRegistrationResponse(examRegistrationRepository.save(reg));
        response.setNegativeCashBalance(negative);
        return response;
    }

    private StudentResponse toStudentResponse(Student s) {
        return StudentResponse.builder()
            .id(s.getId())
            .uuid(s.getUuid())
            .firstName(s.getFirstName())
            .lastName(s.getLastName())
            .phone(s.getPhone())
            .parentPhone(s.getParentPhone())
            .birthDate(s.getBirthDate())
            .gender(s.getGender())
            .marketingSource(s.getMarketingSource())
            .status(s.getStatus())
            .notes(s.getNotes())
            .address(s.getAddress())
            .photoUrl(s.getPhotoUrl())
            .admissionNumber(s.getAdmissionNumber())
            .admissionDate(s.getAdmissionDate())
            .referralStudentId(s.getReferralStudent() != null ? s.getReferralStudent().getId() : null)
            .createdAt(s.getCreatedAt())
            .build();
    }

    /** O'qituvchi uchun: kimligi va holati, aloqa/shaxsiy ma'lumotsiz (E-02). */
    private StudentResponse toLimitedStudentResponse(Student s) {
        return StudentResponse.builder()
            .id(s.getId())
            .uuid(s.getUuid())
            .firstName(s.getFirstName())
            .lastName(s.getLastName())
            .status(s.getStatus())
            .photoUrl(s.getPhotoUrl())
            .build();
    }

    private ExamRegistrationResponse toRegistrationResponse(ExamRegistration r) {
        return ExamRegistrationResponse.builder()
            .id(r.getId())
            .examId(r.getExam().getId())
            .examName(r.getExam().getExamName())
            .studentId(r.getStudent().getId())
            .studentName(r.getStudent().getFirstName() + " " + r.getStudent().getLastName())
            .status(r.getStatus())
            .paymentStatus(r.getFeeStatus())
            .amountDue(r.getAmountDue())
            .amountPaid(r.getAmountPaid())
            .cashTransactionId(r.getCashTransactionId())
            .refundCashTransactionId(r.getRefundCashTransactionId())
            .receiptNumber(r.getReceiptNumber())
            .registrationDate(r.getRegistrationDate())
            .cancelledAt(r.getCancelledAt())
            .cancelReason(r.getCancelReason())
            .notes(r.getNotes())
            .build();
    }

    private Exam buildExam(Exam e, ExamRequest req) {
        e.setExamName(req.getExamName());
        e.setExamType(req.getExamType());
        e.setExamDate(req.getExamDate());
        e.setStartTime(req.getStartTime());
        e.setEndTime(req.getEndTime());
        e.setTotalMarks(req.getTotalMarks());
        e.setPassMarks(req.getPassMarks());
        e.setAcademicYear(req.getAcademicYear());
        if (req.getFee() != null) {
            e.setFee(req.getFee());
        }
        if (req.getGroupId() != null) {
            groupRepository.findById(req.getGroupId()).ifPresent(group -> {
                e.setGroup(group);
                if (group.getTeacher() != null) {
                    e.setTeacher(group.getTeacher());
                }
            });
        }

        if (req.getClassId() != null && !req.getClassId().equals(req.getGroupId())) {
            classRepository.findById(req.getClassId()).ifPresent(e::setClassEntity);
        }
        if (req.getSubjectId() != null) {
            e.setSubject(subjectRepository.findById(req.getSubjectId())
                .orElseThrow(() -> new ResourceNotFoundException("Subject", req.getSubjectId())));
        }
        return e;
    }

    private static Specification<Exam> examSpec(ExamFilter f, Long teacherId, LocalDate today) {
        ListStatus status = f.status() != null ? f.status() : ListStatus.ACTIVE;
        return (root, query, cb) -> {
            List<Predicate> and = new ArrayList<>();
            Path<LocalDate> date = root.get("examDate");
            if (status == ListStatus.INACTIVE) {
                and.add(cb.or(cb.isFalse(root.get("isActive")), cb.isNull(root.get("isActive"))));
            } else {
                and.add(cb.isTrue(root.get("isActive")));
            }
            if (status == ListStatus.UPCOMING) {
                and.add(cb.or(cb.isNull(date), cb.greaterThanOrEqualTo(date, today)));
            } else if (status == ListStatus.PAST) {
                and.add(cb.lessThan(date, today));
            }
            if (f.groupId() != null) {
                and.add(cb.equal(root.get("group").get("id"), f.groupId()));
            }
            if (f.from() != null) {
                and.add(cb.greaterThanOrEqualTo(date, f.from()));
            }
            if (f.to() != null) {
                and.add(cb.lessThanOrEqualTo(date, f.to()));
            }
            if (teacherId != null) {
                // Guruhsiz imtihon ham (o'zi biriktirilgan) — LEFT JOIN
                Join<Exam, Group> group = root.join("group", JoinType.LEFT);
                and.add(cb.or(
                    cb.equal(root.get("teacher").get("id"), teacherId),
                    cb.equal(group.get("teacher").get("id"), teacherId)));
            }
            return cb.and(and.toArray(Predicate[]::new));
        };
    }

    public Exam findExamById(Long id) {
        return examRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Exam", id));
    }

    private void assertExamAccess(Exam exam) {
        if (!teacherAccessService.isCurrentUserTeacher()) {
            return;
        }
        Teacher teacher = teacherAccessService.getCurrentTeacherOrThrow();
        boolean ownsTeacher = exam.getTeacher() != null && teacher.getId().equals(exam.getTeacher().getId());
        boolean ownsGroup = exam.getGroup() != null && exam.getGroup().getTeacher() != null
            && teacher.getId().equals(exam.getGroup().getTeacher().getId());
        if (!ownsTeacher && !ownsGroup) {
            throw new com.crm.exception.ForbiddenException("Bu imtihon sizga tegishli emas");
        }
    }

    private ExamResponse toExamResponse(Exam e) {
        Long groupId = e.getGroup() != null ? e.getGroup().getId() : null;
        String groupName = e.getGroup() != null ? e.getGroup().getGroupName() : null;
        return ExamResponse.builder()
            .id(e.getId()).uuid(e.getUuid()).examName(e.getExamName()).examType(e.getExamType())
            .classId(e.getClassEntity() != null ? e.getClassEntity().getId() : null)
            .className(e.getClassEntity() != null ? e.getClassEntity().getClassName() : null)
            .groupId(groupId)
            .groupName(groupName)
            .subjectId(e.getSubject() != null ? e.getSubject().getId() : null)
            .subjectName(e.getSubject() != null ? e.getSubject().getSubjectName() : null)
            .examDate(e.getExamDate()).startTime(e.getStartTime()).endTime(e.getEndTime())
            .totalMarks(e.getTotalMarks()).passMarks(e.getPassMarks())
            .academicYear(e.getAcademicYear())
            .fee(nz(e.getFee()))
            .isActive(e.getIsActive())
            .createdAt(e.getCreatedAt()).build();
    }

    /**
     * Guruhli bepul imtihon yaratilganda guruhning faol o'quvchilari FREE bo'lib yoziladi.
     * OVERDUE o'quvchi yozilmaydi (D8); davomat sharti bu yerda qo'llanmaydi (avvalgi xulq).
     */
    private void autoRegisterGroupStudents(Exam exam) {
        Long groupId = exam.getGroup().getId();
        List<StudentGroup> enrollments = studentGroupRepository.findActiveByGroupId(groupId);
        for (StudentGroup sg : enrollments) {
            Student student = sg.getStudent();
            if (student == null || student.getStatus() != StudentStatus.ACTIVE) {
                continue;
            }
            if (examRegistrationRepository.existsActive(exam.getId(), student.getId())) {
                continue;
            }
            if (isOverdue(billedEnrollments(exam, student.getId()))) {
                continue;
            }
            ExamRegistration reg = ExamRegistration.builder()
                .exam(exam)
                .student(student)
                .feeStatus(ExamPaymentStatus.FREE)
                .amountDue(BigDecimal.ZERO)
                .amountPaid(BigDecimal.ZERO)
                .status(ExamRegistrationStatus.REGISTERED)
                .registrationDate(LocalDate.now(billingClock))
                .notes("Guruhdan avtomatik ro'yxatga olindi")
                .build();
            examRegistrationRepository.save(reg);
        }
    }

    static Boolean computePassed(BigDecimal score, BigDecimal passMarks) {
        if (score == null || passMarks == null) {
            return null;
        }
        return score.compareTo(passMarks) >= 0;
    }

    private static String formatScoreChange(BigDecimal oldScore, BigDecimal newScore) {
        String from = oldScore != null ? oldScore.stripTrailingZeros().toPlainString() : "—";
        String to = newScore != null ? newScore.stripTrailingZeros().toPlainString() : "—";
        return from + " → " + to;
    }

    private User currentUserOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || "anonymousUser".equals(auth.getName())) {
            return null;
        }
        return userRepository.findByUsername(auth.getName()).orElse(null);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private ExamResultResponse toEmptyResultResponse(Exam exam, Student student) {
        return ExamResultResponse.builder()
            .id(null)
            .examId(exam.getId())
            .examName(exam.getExamName())
            .studentId(student.getId())
            .studentName(student.getFirstName() + " " + student.getLastName())
            .marksObtained(null)
            .totalMarks(exam.getTotalMarks())
            .passMarks(exam.getPassMarks())
            .grade(null)
            .remarks(null)
            .isPassed(null)
            .createdAt(null)
            .build();
    }

    private ExamResultResponse toResultResponse(ExamResult r) {
        String editedByName = null;
        if (r.getEditedBy() != null) {
            editedByName = ((r.getEditedBy().getFirstName() != null ? r.getEditedBy().getFirstName() : "")
                + " "
                + (r.getEditedBy().getLastName() != null ? r.getEditedBy().getLastName() : "")).trim();
            if (editedByName.isEmpty()) {
                editedByName = r.getEditedBy().getUsername();
            }
        }
        return ExamResultResponse.builder()
            .id(r.getId())
            .examId(r.getExam().getId()).examName(r.getExam().getExamName())
            .studentId(r.getStudent().getId())
            .studentName(r.getStudent().getFirstName() + " " + r.getStudent().getLastName())
            .marksObtained(r.getMarksObtained())
            .totalMarks(r.getExam().getTotalMarks())
            .passMarks(r.getExam().getPassMarks())
            .grade(r.getGrade()).remarks(r.getRemarks()).isPassed(r.getIsPassed())
            .editNote(r.getEditNote())
            .editedAt(r.getEditedAt())
            .editedBy(editedByName)
            .createdAt(r.getCreatedAt()).build();
    }
}
