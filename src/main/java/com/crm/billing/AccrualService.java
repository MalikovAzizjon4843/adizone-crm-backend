package com.crm.billing;

import com.crm.entity.BalanceTransaction;
import com.crm.entity.BillingPeriod;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.BalanceTransactionType;
import com.crm.entity.enums.BillingPeriodStatus;
import com.crm.entity.enums.TeacherAttribution;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.BillingPeriodRepository;
import com.crm.repository.StudentGroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Accrual — davr boshlanganda {@code PERIOD_CHARGE} yozish (docs/design/billing-v2.md §3.8).
 *
 * <p><b>Idempotentlik — uch qavat:</b> (1) student → SG qulfi: parallel ikki
 * {@code accrueUpTo} ketma-ket bajariladi; (2) {@code UNIQUE (student_group_id, period_start)};
 * (3) mavjud davrlar o'tkazib yuboriladi. Job bir kunda ikki marta ishlasa yoki
 * server qayta ishga tushsa, yangi yozuv paydo bo'lmaydi.
 *
 * <p><b>Quvib yetish:</b> o'tkazib yuborilgan kunlar uchun barcha boshlangan davrlar
 * yoziladi, {@code effective_date = period_start} (yozilgan kun emas) — holat haqiqiy
 * majburiyat sanasidan hisoblanadi.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccrualService {

    private static final DateTimeFormatter NOTE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final BillingLocks locks;
    private final LedgerService ledger;
    private final BillingPeriodRepository periodRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final BillingProperties properties;
    private final BillingGate gate;
    private final BillingSnapshotService snapshotService;

    /** Yozilgan davrlar va catch-up chegarasi belgisi. */
    public record AccrualResult(Long studentGroupId, List<BillingPeriod> created, boolean catchUpLimitReached) {
    }

    /** O'z qulfini oladi: student → SG. */
    @Transactional
    public AccrualResult accrueUpTo(Long studentGroupId, LocalDate asOf) {
        gate.requireWritable();
        Long studentId = studentGroupRepository.findStudentIdById(studentGroupId)
            .orElseThrow(() -> new ResourceNotFoundException("StudentGroup", studentGroupId));
        StudentGroup sg = locks.lockEnrollmentWithStudent(studentId, studentGroupId);
        return accrueLocked(sg, asOf);
    }

    /** Kunlik job: har SG alohida tranzaksiyada — biri yiqilsa qolganlari davom etadi. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AccrualResult accrueInNewTransaction(Long studentGroupId, LocalDate asOf) {
        return accrueUpTo(studentGroupId, asOf);
    }

    /**
     * Chaqiruvchi student → SG qulfini allaqachon olgan (to'lov, unfreeze, trial
     * konvertatsiyasi). Rejadagi ACCRUAL qatorlari aynan shu yerda yoziladi.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AccrualResult accrueLocked(StudentGroup sg, LocalDate asOf) {
        AccrualCalculator.Result due = plan(sg, asOf);

        List<BillingPeriod> created = new ArrayList<>();
        for (AccrualCalculator.DueCharge charge : due.charges()) {
            BillingPeriod period = periodRepository.save(BillingPeriod.builder()
                .studentGroupId(sg.getId())
                .periodStart(charge.periodStart())
                .periodEnd(charge.periodEnd())
                .fee(charge.fee())
                .discountPercentage(charge.discountPercentage())
                .amount(charge.amount())
                .status(BillingPeriodStatus.CHARGED)
                // Payroll v2 (§8): davr yozilgan paytdagi o'qituvchi — keyin almashsa ham shu qoladi
                .teacherId(currentTeacherId(sg))
                .teacherSource(TeacherAttribution.LIVE)
                .build());
            // d = 100: davr qatori bor (idempotentlik), ledger yozuvi yo'q (§3.5)
            if (charge.amount().signum() > 0) {
                BalanceTransaction tx = ledger.post(LedgerService.Entry.builder()
                    .enrollment(sg)
                    .type(BalanceTransactionType.PERIOD_CHARGE)
                    .amount(charge.amount().negate())
                    .effectiveDate(charge.periodStart())
                    .billingPeriodId(period.getId())
                    .note(periodNote(charge))
                    .build());
                period.setChargeTxId(tx.getId());
            }
            created.add(period);
        }

        if (due.catchUpLimitReached()) {
            log.warn("Accrual catch-up chegarasi ({}) sg={} — qolgan davrlar qo'lda tekshirilsin",
                properties.getMaxCatchUp(), sg.getId());
        }
        if (!created.isEmpty()) {
            log.info("Accrual sg={} → {} davr (oxirgisi {})", sg.getId(), created.size(),
                created.get(created.size() - 1).getPeriodStart());
        }
        snapshotService.refresh(sg);
        return new AccrualResult(sg.getId(), created, due.catchUpLimitReached());
    }

    /**
     * Yozmasdan: {@code asOf} gacha hisoblanishi kerak bo'lgan davrlar. To'lov
     * preview'i (§5.2 qadam 3) va accrual shu bitta hisobdan foydalanadi.
     */
    public AccrualCalculator.Result plan(StudentGroup sg, LocalDate asOf) {
        if (Boolean.TRUE.equals(sg.getBillingHold())) {
            // §9.7 MIGRATION_PENDING: migratsiya qo'lda qayta qo'llanguncha davr yozilmaydi
            return AccrualCalculator.Result.empty();
        }
        List<BillingCalendar.Span> existing = periodRepository.findByStudentGroupIdOrderByPeriodStartAsc(sg.getId())
            .stream().map(p -> new BillingCalendar.Span(p.getPeriodStart(), p.getPeriodEnd())).toList();
        return AccrualCalculator.dueChargesAfter(
            AccrualCalculator.State.of(sg), existing, asOf, properties.getMaxCatchUp());
    }

    private static Long currentTeacherId(StudentGroup sg) {
        return sg.getGroup() != null && sg.getGroup().getTeacher() != null
            ? sg.getGroup().getTeacher().getId() : null;
    }

    static String periodNote(AccrualCalculator.DueCharge charge) {
        return charge.periodStart().format(NOTE_FMT) + "–" + charge.periodEnd().format(NOTE_FMT);
    }
}
