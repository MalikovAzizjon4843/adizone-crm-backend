package com.crm.billing;

import com.crm.entity.Attendance;
import com.crm.entity.BalanceTransaction;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.AttendanceStatus;
import com.crm.entity.enums.BalanceTransactionType;
import com.crm.entity.enums.PaymentType;
import com.crm.repository.BalanceTransactionRepository;
import com.crm.repository.StudentGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * PER_LESSON davomat → ledger (§2: {@code LESSON_CHARGE} / {@code LESSON_REFUND}, §6.9).
 *
 * <p>Idempotent: avvalgi holat ("previous status") emas, shu davomatga bog'langan ledger
 * yig'indisi ({@code net}) kerakli holat bilan solishtiriladi. Qayta yuborish (double-click)
 * hech narsa yozmaydi (C4).
 *
 * <ul>
 *   <li>billable (PRESENT/ABSENT/LATE), {@code net = 0}, hisoblanadigan SG → {@code LESSON_CHARGE −l(sg)}
 *       (joriy narx va chegirma, Money).</li>
 *   <li>billable emas (EXCUSED), {@code net < 0} → {@code LESSON_REFUND +|net|} — <b>asl summa</b>
 *       (narx keyin o'zgargan bo'lsa ham), {@code related_tx_id} = asl charge.</li>
 *   <li>Hisoblanmaydi: sinov, muzlatilgan/yopilgan SG, {@code paymentStartDate} dan oldingi dars (§6.6).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class LessonChargeService {

    private static final List<BalanceTransactionType> LESSON_TYPES =
        List.of(BalanceTransactionType.LESSON_CHARGE, BalanceTransactionType.LESSON_REFUND);

    private final BillingLocks locks;
    private final LedgerService ledger;
    private final BillingSnapshotService snapshotService;
    private final BillingGate gate;
    private final StudentGroupRepository studentGroupRepository;
    private final BalanceTransactionRepository transactionRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<BalanceTransaction> sync(Attendance a) {
        if (a == null || a.getId() == null || a.getStudent() == null || a.getGroup() == null) {
            return Optional.empty();
        }
        List<BalanceTransaction> rows = transactionRepository.findByReferenceIdAndTypeIn(a.getId(), LESSON_TYPES);
        BigDecimal net = rows.stream().map(BalanceTransaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean billable = isBillable(a.getStatus());

        if (billable && net.signum() == 0) {
            StudentGroup candidate = chargeable(a).orElse(null);
            if (candidate == null || EnrollmentPricing.effectiveLessonPrice(candidate).signum() <= 0) {
                return Optional.empty();
            }
            gate.requireWritable();
            StudentGroup sg = locks.lockEnrollmentWithStudent(a.getStudent().getId(), candidate.getId());
            // Qulf ostida qayta tekshiruv: parallel saqlash ikkinchi charge yozmasin
            BigDecimal netLocked = transactionRepository.findByReferenceIdAndTypeIn(a.getId(), LESSON_TYPES).stream()
                .map(BalanceTransaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (netLocked.signum() != 0) {
                return Optional.empty();
            }
            BigDecimal price = EnrollmentPricing.effectiveLessonPrice(sg);
            BalanceTransaction tx = ledger.post(LedgerService.Entry.builder()
                .enrollment(sg)
                .type(BalanceTransactionType.LESSON_CHARGE)
                .teacherId(sg.getGroup() != null && sg.getGroup().getTeacher() != null
                    ? sg.getGroup().getTeacher().getId() : null)
                .amount(price.negate())
                .effectiveDate(a.getAttendanceDate())
                .referenceId(a.getId())
                .note("Dars: " + a.getAttendanceDate() + " (" + a.getStatus() + ")")
                .build());
            snapshotService.refresh(sg);
            return Optional.of(tx);
        }

        if (!billable && net.signum() < 0) {
            BalanceTransaction charge = rows.stream()
                .filter(t -> t.getType() == BalanceTransactionType.LESSON_CHARGE)
                .max(Comparator.comparing(BalanceTransaction::getId))
                .orElseThrow();
            gate.requireWritable();
            StudentGroup sg = locks.lockEnrollmentWithStudent(
                charge.getStudentGroup().getStudent().getId(), charge.getStudentGroup().getId());
            BigDecimal netLocked = transactionRepository.findByReferenceIdAndTypeIn(a.getId(), LESSON_TYPES).stream()
                .map(BalanceTransaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (netLocked.signum() >= 0) {
                return Optional.empty();
            }
            BalanceTransaction tx = ledger.post(LedgerService.Entry.builder()
                .enrollment(sg)
                .type(BalanceTransactionType.LESSON_REFUND)
                .amount(netLocked.negate())
                .effectiveDate(a.getAttendanceDate())
                .referenceId(a.getId())
                .relatedTxId(charge.getId())
                .note("Davomat o'zgardi: " + a.getAttendanceDate() + " → " + a.getStatus())
                .build());
            snapshotService.refresh(sg);
            return Optional.of(tx);
        }
        // billable va allaqachon yozilgan, yoki billable emas va yozuv yo'q — kerakli holat
        return Optional.empty();
    }

    /** Dars sanasida hisoblanadigan PER_LESSON yozilma (faol, sinovda emas, langardan keyin). */
    private Optional<StudentGroup> chargeable(Attendance a) {
        LocalDate date = a.getAttendanceDate();
        return studentGroupRepository
            .findByStudentIdAndGroupIdAndIsActiveTrue(a.getStudent().getId(), a.getGroup().getId())
            .filter(sg -> sg.getPaymentType() == PaymentType.PER_LESSON)
            .filter(sg -> !Boolean.TRUE.equals(sg.getIsTrial()))
            .filter(sg -> sg.getFrozenFrom() == null)
            .filter(sg -> sg.getPaymentStartDate() != null && !date.isBefore(sg.getPaymentStartDate()));
    }

    public static boolean isBillable(AttendanceStatus status) {
        return status == AttendanceStatus.PRESENT
            || status == AttendanceStatus.ABSENT
            || status == AttendanceStatus.LATE;
    }
}
