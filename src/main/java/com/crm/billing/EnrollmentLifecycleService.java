package com.crm.billing;

import com.crm.dto.response.BillingLineDto;
import com.crm.dto.response.FreezeStudentResponse;
import com.crm.entity.BalanceTransaction;
import com.crm.entity.BillingPeriod;
import com.crm.entity.Group;
import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.entity.StudentStatusHistory;
import com.crm.entity.enums.ExitReasonCode;
import com.crm.entity.enums.AttendanceStatus;
import com.crm.entity.enums.BalanceTransactionType;
import com.crm.entity.enums.BillingPeriodStatus;
import com.crm.entity.enums.PaymentStatus;
import com.crm.entity.enums.PaymentType;
import com.crm.entity.enums.StudentStatus;
import com.crm.entity.enums.StudyFormat;
import com.crm.exception.CodedException;
import com.crm.exception.ConflictException;
import com.crm.repository.AttendanceRepository;
import com.crm.repository.BalanceTransactionRepository;
import com.crm.repository.BillingPeriodRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentRepository;
import com.crm.repository.StudentStatusHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Yozilma hayot sikli (billing v2 §6.7–§6.10, §3.6): muzlatish, qaytarish, guruhga
 * ko'chirish, guruhdan chiqish, langarni o'zgartirish. Hammasi ledger orqali (I2) va
 * qulf ostida (§7.2: student → SG).
 *
 * <p>Muzlatish preview'i va o'zi bitta {@link #planFreeze} dan foydalanadi (§5.1 tamoyili).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EnrollmentLifecycleService {

    static final List<AttendanceStatus> BILLABLE =
        List.of(AttendanceStatus.PRESENT, AttendanceStatus.ABSENT, AttendanceStatus.LATE);
    static final String EXIT_FROZEN = "FROZEN";
    static final String EXIT_TRANSFERRED = "TRANSFERRED";
    static final int FREEZE_MAX_BACK_DAYS = 30;

    private final BillingLocks locks;
    private final LedgerService ledger;
    private final AccrualService accrualService;
    private final BillingSnapshotService snapshotService;
    private final BillingStatusService statusService;
    private final BillingGate gate;
    private final StudentGroupRepository studentGroupRepository;
    private final StudentRepository studentRepository;
    private final BillingPeriodRepository periodRepository;
    private final BalanceTransactionRepository transactionRepository;
    private final AttendanceRepository attendanceRepository;
    private final StudentStatusHistoryRepository historyRepository;
    private final Clock billingClock;

    // ════════════════════════════════════════════════════════════════════
    // Muzlatish (§6.7)
    // ════════════════════════════════════════════════════════════════════

    /** Bitta davr uchun qaytarim. {@code period == null} — davr hali yozilmagan (preview). */
    public record PlannedRefund(BillingPeriod period, LocalDate periodStart, LocalDate periodEnd,
                                BigDecimal amount, BigDecimal refund, boolean full) {
    }

    public record FreezePlan(StudentGroup enrollment, LocalDate freezeDate, List<BillingLineDto> lines,
                             List<PlannedRefund> refunds, BigDecimal refundTotal,
                             BigDecimal balanceBefore, BillingSnapshot after) {
    }

    /**
     * Yozmasdan: muzlatish nimani yozadi. MONTHLY — {@code freezeDate} ni o'z ichiga olgan
     * davrdan proporsional, keyin boshlangan davrlardan to'liq {@code PERIOD_REFUND};
     * hali yozilmagan (bugungacha boshlangan) davrlar "pending" PERIOD_CHARGE bo'lib
     * ko'rsatiladi — freeze ularni avval yozadi. PER_LESSON — ledger yozuvi yo'q.
     */
    public FreezePlan planFreeze(StudentGroup sg, LocalDate freezeDate, LocalDate today) {
        validateFreezeDate(sg, freezeDate, today);
        List<BillingLineDto> lines = new ArrayList<>();
        List<FifoDebt.Line> planned = new ArrayList<>();
        List<LocalDate> plannedStarts = new ArrayList<>();
        List<PlannedRefund> refunds = new ArrayList<>();

        if (!isPerLesson(sg)) {
            List<PlannedRefund> candidates = new ArrayList<>();
            for (BillingPeriod p : periodRepository.findByStudentGroupIdOrderByPeriodStartAsc(sg.getId())) {
                if (p.getStatus() == BillingPeriodStatus.CHARGED && p.getChargeTxId() != null
                        && Money.nz(p.getAmount()).signum() > 0) {
                    candidates.add(new PlannedRefund(p, p.getPeriodStart(), p.getPeriodEnd(), p.getAmount(), null, false));
                }
            }
            for (AccrualCalculator.DueCharge due : accrualService.plan(sg, today).charges()) {
                plannedStarts.add(due.periodStart());
                if (due.amount().signum() <= 0) {
                    continue;
                }
                lines.add(BillingLineDto.builder()
                    .type(BalanceTransactionType.PERIOD_CHARGE.name())
                    .amount(Money.normalize(due.amount().negate()))
                    .effectiveDate(due.periodStart())
                    .note(AccrualService.periodNote(due))
                    .pending(true)
                    .build());
                planned.add(new FifoDebt.Line(null, due.amount().negate(), due.periodStart(), null));
                candidates.add(new PlannedRefund(null, due.periodStart(), due.periodEnd(), due.amount(), null, false));
            }

            for (PlannedRefund c : candidates) {
                if (c.periodEnd().isBefore(freezeDate)) {
                    continue;
                }
                boolean full = !c.periodStart().isBefore(freezeDate);
                BigDecimal refund = full ? c.amount() : Money.proportion(c.amount(),
                    ChronoUnit.DAYS.between(freezeDate, c.periodEnd()) + 1,
                    ChronoUnit.DAYS.between(c.periodStart(), c.periodEnd()) + 1);
                if (refund.signum() <= 0) {
                    continue;
                }
                refunds.add(new PlannedRefund(c.period(), c.periodStart(), c.periodEnd(), c.amount(), refund, full));
                lines.add(BillingLineDto.builder()
                    .type(BalanceTransactionType.PERIOD_REFUND.name())
                    .amount(Money.normalize(refund))
                    .effectiveDate(freezeDate)
                    .note(refundNote(c.periodStart(), c.periodEnd(), full))
                    .pending(true)
                    .build());
                planned.add(new FifoDebt.Line(null, refund, freezeDate, null));
            }
        }

        BigDecimal total = refunds.stream().map(PlannedRefund::refund).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal before = Money.nz(transactionRepository.sumAmountByStudentGroupId(sg.getId()));
        BillingSnapshot after = statusService.snapshotWith(sg, planned, plannedStarts, today);
        return new FreezePlan(sg, freezeDate, lines, refunds, total, before, after);
    }

    @Transactional(readOnly = true)
    public FreezeStudentResponse previewFreeze(Long studentId, Long groupId, LocalDate freezeDate) {
        LocalDate today = statusService.today();
        StudentGroup sg = resolveActive(studentId, groupId);
        FreezePlan plan = planFreeze(sg, freezeDate != null ? freezeDate : today, today);
        boolean allFrozen = otherActive(studentId, sg.getId()) == 0;
        return toResponse(plan, frozenStatus(plan.after(), today), allFrozen);
    }

    /** {@code POST /api/students/{id}/freeze}: faqat tanlangan yozilma (§6.7). */
    @Transactional
    public FreezeStudentResponse freeze(Long studentId, Long groupId, LocalDate freezeDate,
                                        String reason, String note) {
        gate.requireWritable();
        StudentGroup candidate = resolveActive(studentId, groupId);
        return freezeEnrollment(candidate.getId(), freezeDate, reason, note);
    }

    /** Avto-arxiv (§13 #25): har SG alohida tranzaksiyada. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FreezeStudentResponse freezeInNewTransaction(Long studentGroupId, String reason, String note) {
        gate.requireWritable();
        return freezeEnrollment(studentGroupId, null, reason, note);
    }

    private FreezeStudentResponse freezeEnrollment(Long studentGroupId, LocalDate requestedDate,
                                                   String reason, String note) {
        LocalDate today = statusService.today();
        LocalDate freezeDate = requestedDate != null ? requestedDate : today;
        Long studentId = studentGroupRepository.findStudentIdById(studentGroupId)
            .orElseThrow(() -> CodedException.notFound("error.studentGroup.notFound", studentGroupId));
        StudentGroup sg = locks.lockEnrollmentWithStudent(studentId, studentGroupId);
        if (!Boolean.TRUE.equals(sg.getIsActive()) || sg.getFrozenFrom() != null) {
            throw new ConflictException("student.freeze.already");
        }
        validateFreezeDate(sg, freezeDate, today);
        BigDecimal before = Money.nz(transactionRepository.sumAmountByStudentGroupId(sg.getId()));

        List<BillingLineDto> written = new ArrayList<>();
        for (BillingPeriod p : accrualService.accrueLocked(sg, today).created()) {
            if (p.getChargeTxId() != null) {
                written.add(BillingLineDto.builder()
                    .type(BalanceTransactionType.PERIOD_CHARGE.name())
                    .amount(Money.normalize(p.getAmount().negate()))
                    .effectiveDate(p.getPeriodStart())
                    .note(AccrualService.periodNote(new AccrualCalculator.DueCharge(
                        p.getPeriodStart(), p.getPeriodEnd(), p.getFee(), p.getDiscountPercentage(), p.getAmount())))
                    .ledgerTxId(p.getChargeTxId())
                    .build());
            }
        }

        FreezePlan plan = planFreeze(sg, freezeDate, today);
        for (PlannedRefund r : plan.refunds()) {
            BillingPeriod period = r.period();
            if (period == null) {
                // accrual catch-up chegarasi: davr yozilmay qoldi — avval accrual tugallansin
                throw new ConflictException("billing.catchUp.limit");
            }
            BalanceTransaction tx = ledger.post(LedgerService.Entry.builder()
                .enrollment(sg)
                .type(BalanceTransactionType.PERIOD_REFUND)
                .amount(r.refund())
                .effectiveDate(freezeDate)
                .relatedTxId(period.getChargeTxId())
                .billingPeriodId(period.getId())
                .note(refundNote(r.periodStart(), r.periodEnd(), r.full()))
                .build());
            period.setRefundedAmount(Money.nz(period.getRefundedAmount()).add(r.refund()));
            period.setStatus(r.full() ? BillingPeriodStatus.REFUNDED : BillingPeriodStatus.PARTIALLY_REFUNDED);
            periodRepository.save(period);
            written.add(BillingLineDto.builder()
                .type(tx.getType().name())
                .amount(Money.normalize(tx.getAmount()))
                .effectiveDate(freezeDate)
                .note(tx.getNote())
                .ledgerTxId(tx.getId())
                .build());
        }

        sg.setIsActive(false);
        sg.setFrozenFrom(freezeDate);
        sg.setExitReason(EXIT_FROZEN);
        // Direktor dashboardi (§3.4): avto-arxiv alohida sabab
        sg.setExitReasonCode("AUTO_ARCHIVE".equals(reason) ? ExitReasonCode.AUTO_ARCHIVE : ExitReasonCode.FROZEN);
        sg.setExitDate(freezeDate);
        sg.setLeaveDate(freezeDate);
        sg.setExitNotes(note);
        studentGroupRepository.save(sg);
        BillingSnapshot after = snapshotService.refresh(sg);

        Student student = sg.getStudent();
        boolean allFrozen = otherActive(studentId, sg.getId()) == 0;
        String previous = student.getStatus() != null ? student.getStatus().name() : StudentStatus.ACTIVE.name();
        if (allFrozen) {
            student.setStatus(StudentStatus.FROZEN);
            studentRepository.save(student);
        }
        history(student, previous, student.getStatus() != null ? student.getStatus().name() : previous,
            reason != null && !reason.isBlank() ? reason : EXIT_FROZEN,
            "Muzlatildi: " + groupName(sg) + (note != null && !note.isBlank() ? " | " + note : ""),
            after.balance());

        FreezePlan result = new FreezePlan(sg, freezeDate, written, plan.refunds(), plan.refundTotal(), before, after);
        return toResponse(result, after.status(), allFrozen);
    }

    /** §13 #2: {@code ≤ bugun}, 30 kundan eski emas, oxirgi billable davomatdan oldin emas. */
    private void validateFreezeDate(StudentGroup sg, LocalDate freezeDate, LocalDate today) {
        if (freezeDate.isAfter(today)) {
            throw CodedException.badRequest("freeze.date.future");
        }
        if (freezeDate.isBefore(today.minusDays(FREEZE_MAX_BACK_DAYS))) {
            throw CodedException.badRequest("freeze.date.tooOld", FREEZE_MAX_BACK_DAYS);
        }
        if (sg.getStudent() != null && sg.getGroup() != null) {
            LocalDate last = attendanceRepository.findLastDateByStatuses(
                sg.getStudent().getId(), sg.getGroup().getId(), BILLABLE);
            if (last != null && freezeDate.isBefore(last)) {
                throw CodedException.badRequest("freeze.date.beforeAttendance", last);
            }
        }
    }

    private FreezeStudentResponse toResponse(FreezePlan plan, PaymentStatus statusAfter, boolean allFrozen) {
        StudentGroup sg = plan.enrollment();
        int lessons = sg.getStudent() != null && sg.getGroup() != null
            ? (int) attendanceRepository.countByStudentAndGroupAndStatuses(
                sg.getStudent().getId(), sg.getGroup().getId(), BILLABLE)
            : 0;
        BigDecimal balanceAfter = Money.normalize(plan.after().balance());
        FreezeStudentResponse.FrozenGroupBreakdown row = FreezeStudentResponse.FrozenGroupBreakdown.builder()
            .groupId(sg.getGroup() != null ? sg.getGroup().getId() : null)
            .groupName(groupName(sg))
            .lessonsAttended(lessons)
            .lessonsUsed(lessons)
            .lessonPrice(isPerLesson(sg) ? EnrollmentPricing.effectiveLessonPrice(sg) : BigDecimal.ZERO)
            .balance(balanceAfter)
            .build();
        return FreezeStudentResponse.builder()
            .studentId(sg.getStudent() != null ? sg.getStudent().getId() : null)
            .totalBalance(balanceAfter)
            .groups(new ArrayList<>(List.of(row)))
            .freezeDate(plan.freezeDate())
            .studentGroupId(sg.getId())
            .refundLines(plan.lines())
            .refundTotal(Money.normalize(plan.refundTotal()))
            .balanceBefore(Money.normalize(plan.balanceBefore()))
            .balanceAfter(balanceAfter)
            .debtAfter(Money.normalize(plan.after().debt()))
            .statusAfter(statusAfter != null ? statusAfter.name() : null)
            .studentStatusAfter(allFrozen ? StudentStatus.FROZEN.name() : StudentStatus.ACTIVE.name())
            .build();
    }

    /** Preview: SG hali muzlatilmagan — qarz bo'lmasa holat FROZEN bo'ladi (§4.2, qarz ustun). */
    private PaymentStatus frozenStatus(BillingSnapshot after, LocalDate today) {
        PaymentStatus money = statusService.statusOf(after.balance(), after.debtSince(), today);
        return money == PaymentStatus.PAID ? PaymentStatus.FROZEN : money;
    }

    // ════════════════════════════════════════════════════════════════════
    // Qaytarish — unfreeze (§6.7)
    // ════════════════════════════════════════════════════════════════════

    /**
     * O'sha SG qayta faollashadi (yangi SG yo'q, balans ko'chmaydi). MONTHLY da yangi
     * langar {@code paymentStartDate} dan accrual (§3.6) — bugungacha boshlangan davr
     * darhol yoziladi.
     */
    @Transactional
    public StudentGroup unfreeze(Long studentId, Long groupId, LocalDate paymentStartDate) {
        gate.requireWritable();
        LocalDate today = statusService.today();
        if (studentGroupRepository.findByStudentIdAndGroupIdAndIsActiveTrue(studentId, groupId).isPresent()) {
            throw CodedException.badRequest("student.unfreeze.alreadyActive");
        }
        StudentGroup candidate = studentGroupRepository.findByStudentId(studentId).stream()
            .filter(sg -> sg.getGroup() != null && groupId.equals(sg.getGroup().getId()))
            .filter(EnrollmentLifecycleService::isFrozen)
            .max(Comparator.comparing(StudentGroup::getId))
            .orElseThrow(() -> CodedException.badRequest("student.unfreeze.notFrozen"));

        StudentGroup sg = locks.lockEnrollmentWithStudent(studentId, candidate.getId());
        Group group = sg.getGroup();
        long current = studentGroupRepository.countByGroupIdAndIsActiveTrue(group.getId());
        if (group.getMaxStudents() != null && current >= group.getMaxStudents()) {
            throw CodedException.badRequest("group.full", group.getMaxStudents());
        }

        LocalDate frozenFrom = sg.getFrozenFrom() != null ? sg.getFrozenFrom() : sg.getLeaveDate();
        LocalDate anchor = paymentStartDate != null ? paymentStartDate : today;
        if (!isPerLesson(sg)) {
            LocalDate earliest = earliestAnchor(sg);
            if (frozenFrom != null && frozenFrom.isAfter(earliest)) {
                earliest = frozenFrom;
            }
            if (anchor.isBefore(earliest)) {
                throw new ConflictException("billing.anchor.overlap", earliest);
            }
        }

        sg.setIsActive(true);
        sg.setFrozenFrom(null);
        sg.setExitReason(null);
        sg.setExitReasonCode(null);
        sg.setExitDate(null);
        sg.setLeaveDate(null);
        sg.setExitNotes(null);
        sg.setPaymentStartDate(anchor);
        studentGroupRepository.save(sg);
        accrualService.accrueLocked(sg, today);

        Student student = sg.getStudent();
        String previous = student.getStatus() != null ? student.getStatus().name() : StudentStatus.FROZEN.name();
        student.setStatus(StudentStatus.ACTIVE);
        studentRepository.save(student);
        history(student, previous, StudentStatus.ACTIVE.name(), "UNFROZEN",
            "Muzlatishdan chiqarildi: " + groupName(sg) + ", to'lov " + anchor + " dan", sg.getBalance());
        snapshotService.refresh(sg);
        return sg;
    }

    // ════════════════════════════════════════════════════════════════════
    // Guruhga ko'chirish (§6.8)
    // ════════════════════════════════════════════════════════════════════

    public record Transfer(StudentGroup from, StudentGroup to, BigDecimal moved) {
    }

    /**
     * Eski SG yopiladi, yangisi narx shartlari bilan ochiladi, balans
     * {@code TRANSFER_OUT}/{@code TRANSFER_IN} juftligi bilan to'liq ko'chadi. Langar
     * uzluksiz: yangi SG eski SG ning keyingi hisoblanmagan davridan boshlanadi.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Transfer transfer(Long studentId, Long fromGroupId, Group toGroup, StudyFormat format,
                             String exitReason, String exitNote, LocalDate joinDate) {
        gate.requireWritable();
        LocalDate today = statusService.today();
        StudentGroup candidate = studentGroupRepository
            .findByStudentIdAndGroupIdAndIsActiveTrue(studentId, fromGroupId)
            .orElseThrow(() -> CodedException.notFound("error.studentGroup.notFound", fromGroupId));
        StudentGroup from = locks.lockEnrollmentWithStudent(studentId, candidate.getId());
        if (!Boolean.TRUE.equals(from.getIsActive())) {
            throw CodedException.notFound("error.studentGroup.notFound", fromGroupId);
        }
        accrualService.accrueLocked(from, today);

        StudentGroup to = studentGroupRepository.save(StudentGroup.builder()
            .student(from.getStudent())
            .group(toGroup)
            .joinDate(joinDate != null ? joinDate : today)
            .paymentType(from.getPaymentType() != null ? from.getPaymentType() : PaymentType.MONTHLY)
            .paymentStartDate(continuedAnchor(from, today))
            .monthlyPriceOverride(from.getMonthlyPriceOverride())
            .discountPercentage(from.getDiscountPercentage())
            .lessonPrice(from.getLessonPrice())
            .isTrial(Boolean.TRUE.equals(from.getIsTrial()))
            // Sinov davom etadi: birinchi kelgan kun ko'chadi (director-dashboard §1.5)
            .trialStartedAt(Boolean.TRUE.equals(from.getIsTrial()) ? from.getTrialStartedAt() : null)
            .studyFormat(format != null ? format : from.getStudyFormat())
            .isActive(true)
            .balance(BigDecimal.ZERO)
            .lessonsAttended(0)
            .build());
        studentGroupRepository.flush();
        // Ikkala SG ham shu tranzaksiyada qulflangan bo'lsin (yangisi hali hech kimga ko'rinmaydi)
        to = locks.lockEnrollmentWithStudent(studentId, to.getId());

        BillingSnapshot fromState = statusService.snapshot(from, today);
        BigDecimal moved = Money.nz(fromState.balance());
        if (moved.signum() != 0) {
            BalanceTransaction out = ledger.post(LedgerService.Entry.builder()
                .enrollment(from)
                .type(BalanceTransactionType.TRANSFER_OUT)
                .amount(moved.negate())
                .effectiveDate(today)
                .referenceId(to.getId())
                .note("Ko'chirildi → " + groupName(to))
                .build());
            LocalDate inDate = moved.signum() < 0 && fromState.debtSince() != null ? fromState.debtSince() : today;
            ledger.post(LedgerService.Entry.builder()
                .enrollment(to)
                .type(BalanceTransactionType.TRANSFER_IN)
                .amount(moved)
                .effectiveDate(inDate)
                .referenceId(from.getId())
                .relatedTxId(out.getId())
                .note("Ko'chirildi ← " + groupName(from))
                .build());
        }

        from.setIsActive(false);
        from.setLeaveDate(today);
        from.setExitDate(today);
        from.setExitReason(exitReason != null && !exitReason.isBlank() ? exitReason : EXIT_TRANSFERRED);
        from.setExitReasonCode(ExitReasonCode.TRANSFERRED);
        from.setExitNotes(exitNote);
        studentGroupRepository.save(from);

        accrualService.accrueLocked(to, today);
        snapshotService.refresh(from);
        snapshotService.refresh(to);
        return new Transfer(from, to, moved);
    }

    /** Preview (guruhga ko'chirish, phase6-api §5): ko'chirilsa yangi SG qaysi sanadan hisoblanadi. Hech narsa yozmaydi. */
    public LocalDate transferAnchor(StudentGroup from) {
        return continuedAnchor(from, statusService.today());
    }

    /**
     * Yangi SG langari: MONTHLY va sinovda emas — eski SG ning keyingi hisoblanmagan
     * davr boshi (joriy davr eski SG da olingan, ikki marta olinmaydi). Davrlar bo'lmasa —
     * eski langar (kelajakda bo'lsa) yoki bugun.
     */
    private LocalDate continuedAnchor(StudentGroup from, LocalDate today) {
        LocalDate anchor = from.getPaymentStartDate();
        if (isPerLesson(from) || Boolean.TRUE.equals(from.getIsTrial())) {
            return anchor != null && anchor.isAfter(today) ? anchor : today;
        }
        BillingPeriod last = periodRepository.findByStudentGroupIdOrderByPeriodStartAsc(from.getId()).stream()
            .filter(p -> anchor == null || !p.getPeriodStart().isBefore(anchor))
            .max(Comparator.comparing(BillingPeriod::getPeriodStart))
            .orElse(null);
        if (last != null) {
            return last.getPeriodEnd().plusDays(1);
        }
        return anchor != null && anchor.isAfter(today) ? anchor : today;
    }

    // ════════════════════════════════════════════════════════════════════
    // Guruhdan chiqish (§6.10)
    // ════════════════════════════════════════════════════════════════════

    /**
     * SG yopiladi ({@code leaveDate = bugun}); avval bugungacha boshlangan davrlar
     * yoziladi, keyingilari hisoblanmaydi. Joriy davr qaytarilmaydi (§13 #3), balans SG da qoladi.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public StudentGroup leave(Long studentId, Long groupId, String exitReason, String exitNotes) {
        return leave(studentId, groupId, exitReason, exitNotes, null);
    }

    /**
     * @param reasonCode director-dashboard §3.4; null bo'lsa {@code exitReason} matnidan
     *                   ({@link ExitReasonCode#fromLegacy}, §7 #12).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public StudentGroup leave(Long studentId, Long groupId, String exitReason, String exitNotes,
                              ExitReasonCode reasonCode) {
        gate.requireWritable();
        LocalDate today = statusService.today();
        StudentGroup candidate = studentGroupRepository
            .findByStudentIdAndGroupIdAndIsActiveTrue(studentId, groupId)
            .orElseThrow(() -> CodedException.notFound("error.studentGroup.notFound", groupId));
        StudentGroup sg = locks.lockEnrollmentWithStudent(studentId, candidate.getId());
        accrualService.accrueLocked(sg, today);
        com.crm.dashboard.TrialTracking.markClosed(sg);
        sg.setIsActive(false);
        sg.setLeaveDate(today);
        sg.setExitDate(today);
        if (exitReason != null) {
            sg.setExitReason(exitReason);
        }
        sg.setExitReasonCode(reasonCode != null ? reasonCode : ExitReasonCode.fromLegacy(exitReason));
        if (exitNotes != null) {
            sg.setExitNotes(exitNotes);
        }
        studentGroupRepository.save(sg);
        snapshotService.refresh(sg);
        return sg;
    }

    // ════════════════════════════════════════════════════════════════════
    // Langarni o'zgartirish (§3.6) — PATCH /payment-start-date
    // ════════════════════════════════════════════════════════════════════

    /**
     * Yangi langar oldingi davrlar bilan ustma-ust tushmasligi kerak — aks holda 409
     * {@code billing.anchor.overlap}. ACCOUNTANT bir davrdan ko'p orqaga sanalay olmaydi (§13 #13).
     */
    @Transactional
    public StudentGroup reanchor(Long studentId, Long groupId, LocalDate anchor, Boolean isTrial) {
        gate.requireWritable();
        if (anchor == null) {
            throw CodedException.badRequest("billing.anchor.required");
        }
        LocalDate today = statusService.today();
        List<StudentGroup> open = studentGroupRepository.findActiveByStudentId(studentId);
        StudentGroup candidate;
        if (groupId != null) {
            candidate = open.stream().filter(sg -> sg.getGroup() != null && groupId.equals(sg.getGroup().getId()))
                .findFirst().orElseThrow(() -> CodedException.notFound("error.studentGroup.notFound", groupId));
        } else if (open.size() == 1) {
            candidate = open.get(0);
        } else if (open.isEmpty()) {
            throw CodedException.badRequest("payment.enrollment.required");
        } else {
            throw CodedException.badRequest("payment.group.required");
        }
        if (BillingAuth.hasAnyRole("ACCOUNTANT") && !BillingAuth.hasAnyRole("SUPER_ADMIN", "ADMIN")
                && anchor.isBefore(today.minusMonths(1))) {
            throw CodedException.forbidden("billing.anchor.backdateForbidden");
        }

        StudentGroup sg = locks.lockEnrollmentWithStudent(studentId, candidate.getId());
        if (!isPerLesson(sg)) {
            LocalDate earliest = earliestAnchor(sg);
            if (anchor.isBefore(earliest)) {
                throw new ConflictException("billing.anchor.overlap", earliest);
            }
        }
        sg.setPaymentStartDate(anchor);
        if (isTrial != null) {
            boolean wasTrial = Boolean.TRUE.equals(sg.getIsTrial());
            sg.setIsTrial(isTrial);
            // Direktor dashboardi (§3.3): sinov natijasi
            if (wasTrial && !isTrial) {
                com.crm.dashboard.TrialTracking.markConverted(sg, today);
            } else if (!wasTrial && isTrial) {
                com.crm.dashboard.TrialTracking.markTrialAgain(sg);
            }
        }
        studentGroupRepository.save(sg);
        accrualService.accrueLocked(sg, today);
        snapshotService.refresh(sg);
        return sg;
    }

    /**
     * Eng erta ruxsat etilgan langar: oxirgi to'liq hisoblangan davr oxiridan keyin va
     * mavjud davr boshlaridan keyin (UNIQUE(sg, period_start) — qaytarilgan davr qayta
     * yozilmaydi, §3.6).
     */
    LocalDate earliestAnchor(StudentGroup sg) {
        LocalDate earliest = LocalDate.MIN;
        for (BillingPeriod p : periodRepository.findByStudentGroupIdOrderByPeriodStartAsc(sg.getId())) {
            LocalDate bound = p.getStatus() == BillingPeriodStatus.CHARGED
                ? p.getPeriodEnd().plusDays(1) : p.getPeriodStart().plusDays(1);
            if (bound.isAfter(earliest)) {
                earliest = bound;
            }
        }
        return earliest;
    }

    // ── yordamchilar ────────────────────────────────────────────────────

    /** Muzlatish/oldindan ko'rish uchun: guruh berilsa — o'sha faol SG; aks holda yagona faol SG. */
    private StudentGroup resolveActive(Long studentId, Long groupId) {
        if (!studentRepository.existsById(studentId)) {
            throw CodedException.notFound("error.student.notFound", studentId);
        }
        if (groupId != null) {
            return studentGroupRepository.findByStudentIdAndGroupIdAndIsActiveTrue(studentId, groupId)
                .orElseThrow(() -> CodedException.badRequest("student.freeze.noActiveGroup"));
        }
        List<StudentGroup> active = studentGroupRepository.findActiveByStudentId(studentId).stream()
            .filter(sg -> Boolean.TRUE.equals(sg.getIsActive()))
            .toList();
        if (active.isEmpty()) {
            throw CodedException.badRequest("student.freeze.noActiveGroup");
        }
        if (active.size() > 1) {
            throw CodedException.badRequest("payment.group.required");
        }
        return active.get(0);
    }

    private long otherActive(Long studentId, Long exceptSgId) {
        return studentGroupRepository.findByStudentId(studentId).stream()
            .filter(sg -> Boolean.TRUE.equals(sg.getIsActive()) && !sg.getId().equals(exceptSgId))
            .count();
    }

    static boolean isFrozen(StudentGroup sg) {
        return sg.getFrozenFrom() != null
            || (!Boolean.TRUE.equals(sg.getIsActive()) && EXIT_FROZEN.equals(sg.getExitReason()));
    }

    private static boolean isPerLesson(StudentGroup sg) {
        return sg.getPaymentType() == PaymentType.PER_LESSON;
    }

    private static String groupName(StudentGroup sg) {
        return sg.getGroup() != null ? sg.getGroup().getGroupName() : "";
    }

    private static String refundNote(LocalDate start, LocalDate end, boolean full) {
        return (full ? "Davr to'liq qaytarildi: " : "Muzlatish qaytarimi: ") + start + "–" + end;
    }

    private void history(Student student, String from, String to, String reason, String notes, BigDecimal balance) {
        StudentStatusHistory h = new StudentStatusHistory();
        h.setStudent(student);
        h.setFromStatus(from);
        h.setToStatus(to);
        h.setReason(reason);
        h.setNotes(notes);
        h.setBalanceSnapshot(balance);
        h.setChangedAt(LocalDateTime.now(billingClock));
        historyRepository.save(h);
    }
}
