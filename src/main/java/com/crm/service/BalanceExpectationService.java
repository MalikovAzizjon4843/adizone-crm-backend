package com.crm.service;

import com.crm.entity.BalanceTransaction;
import com.crm.entity.Payment;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.AttendanceStatus;
import com.crm.entity.enums.BalanceTransactionType;
import com.crm.entity.enums.PaymentStatus;
import com.crm.entity.enums.PaymentType;
import com.crm.repository.AttendanceRepository;
import com.crm.repository.BalanceTransactionRepository;
import com.crm.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Enrollment balansini MUSTAQIL manbalardan hisoblaydi.
 *
 * <p>Bu sinf balans daftarini (balance_transactions) o'zi bilan solishtirmaydi —
 * u {@code payments} va {@code attendance} jadvallaridan kutilgan balansni qayta
 * quradi, so'ng {@code StudentGroup.balance} bilan solishtiradi. Shu sababli u
 * yetishmayotgan yoki ortiqcha ledger yozuvlarini ko'ra oladi.
 *
 * <pre>
 * expected = cashIn                       // SUM(payments.cash_amount), PAID
 *          - periodCost                   // MONTHLY: SUM(PeriodChargeFormula.debit(...))
 *          - lessonCost                   // PER_LESSON: lessonPrice x billable davomat
 *          + carriedLedger                // FREEZE / UNFREEZE / PERIOD_REFUND / MANUAL_ADJUST
 * </pre>
 *
 * <p>{@code periodCost} {@link PeriodChargeFormula} orqali hisoblanadi — to'lov
 * yozayotgan {@code PaymentService} bilan AYNAN bir xil formula.</p>
 *
 * <p>Ta'mirlash yozgan MANUAL_ADJUST lar {@link #REPAIR_NOTE_PREFIX} bo'yicha
 * chiqarib tashlanadi — aks holda takroriy ishga tushirishda ikki marta sanalardi.
 *
 * <p>Bitta enrollment uchun 2-3 ta so'rov bajaradi; admin diagnostikasi uchun
 * mo'ljallangan, issiq yo'lda ishlatilmasin.
 */
@Service
@RequiredArgsConstructor
public class BalanceExpectationService {

    /** Ta'mirlash yozuvlarining izoh prefiksi — qayta hisobda hisobga olinmaydi. */
    public static final String REPAIR_NOTE_PREFIX = "[ledger-repair]";

    private static final List<AttendanceStatus> BILLABLE_STATUSES =
        List.of(AttendanceStatus.PRESENT, AttendanceStatus.ABSENT, AttendanceStatus.LATE);

    /** Qayta hisoblanmaydigan, o'z holicha olinadigan yozuvlar. */
    private static final Set<BalanceTransactionType> CARRIED_TYPES = EnumSet.of(
        BalanceTransactionType.FREEZE,
        BalanceTransactionType.UNFREEZE,
        BalanceTransactionType.PERIOD_REFUND,
        BalanceTransactionType.MANUAL_ADJUST);

    private final PaymentRepository paymentRepository;
    private final AttendanceRepository attendanceRepository;
    private final BalanceTransactionRepository balanceTransactionRepository;

    /** Yetishmayotgan PERIOD_CHARGE. */
    public record MissingPeriodCharge(
        Long paymentId,
        String receiptNumber,
        LocalDate periodStart,
        LocalDate periodEnd,
        int months,
        BigDecimal amount) {}

    /** PAYMENT krediti cashAmount ga teng emas. */
    public record WrongCredit(
        Long paymentId,
        String receiptNumber,
        BigDecimal expected,
        BigDecimal recorded) {}

    /** MONTHLY guruhda yozilgan LESSON_CHARGE / LESSON_REFUND. */
    public record StrayLessonCharge(
        Long transactionId,
        BalanceTransactionType type,
        BigDecimal amount,
        LocalDateTime createdAt,
        String note) {}

    /** student_group_id bo'sh to'lov — qaysi enrollmentga tegishli ekani noaniq. */
    public record UnlinkedPayment(
        Long paymentId,
        String receiptNumber,
        LocalDate paymentDate,
        BigDecimal cashAmount) {}

    public record Expectation(
        Long studentGroupId,
        Long studentId,
        String studentName,
        String groupName,
        PaymentType paymentType,
        BigDecimal cashIn,
        BigDecimal periodCost,
        BigDecimal lessonCost,
        BigDecimal carriedLedger,
        BigDecimal ledgerSum,
        BigDecimal storedBalance,
        BigDecimal expectedBalance,
        BigDecimal diff,
        List<MissingPeriodCharge> missingPeriodCharges,
        List<WrongCredit> wrongCredits,
        List<StrayLessonCharge> strayLessonCharges,
        List<UnlinkedPayment> unlinkedPayments) {

        public boolean hasIssue() {
            return diff.compareTo(BigDecimal.ZERO) != 0
                || !missingPeriodCharges.isEmpty()
                || !wrongCredits.isEmpty()
                || !strayLessonCharges.isEmpty()
                || !unlinkedPayments.isEmpty();
        }

        /**
         * Bog'lanmagan to'lov bo'lsa kutilgan balans TO'LIQ EMAS —
         * avtomatik tuzatish qilinmasligi kerak.
         */
        public boolean safeToApply() {
            return unlinkedPayments.isEmpty();
        }
    }

    public Expectation compute(StudentGroup sg) {
        PaymentType type = sg.getPaymentType() != null ? sg.getPaymentType() : PaymentType.MONTHLY;

        List<Payment> payments = paymentRepository
            .findByStudentGroup_IdAndStatusOrderByPaymentDateAscIdAsc(sg.getId(), PaymentStatus.PAID);
        List<BalanceTransaction> txs =
            balanceTransactionRepository.findByStudentGroup_IdOrderByIdAsc(sg.getId());

        Map<Long, BigDecimal> creditByPayment = new HashMap<>();
        Set<Long> chargedPayments = new HashSet<>();
        BigDecimal carriedLedger = BigDecimal.ZERO;
        BigDecimal ledgerSum = BigDecimal.ZERO;
        List<StrayLessonCharge> strays = new ArrayList<>();

        for (BalanceTransaction t : txs) {
            BigDecimal amount = nz(t.getAmount());
            ledgerSum = ledgerSum.add(amount);

            switch (t.getType()) {
                case PAYMENT -> {
                    if (t.getReferenceId() != null) {
                        creditByPayment.merge(t.getReferenceId(), amount, BigDecimal::add);
                    }
                }
                case PERIOD_CHARGE -> {
                    if (t.getReferenceId() != null) {
                        chargedPayments.add(t.getReferenceId());
                    }
                }
                case LESSON_CHARGE, LESSON_REFUND -> {
                    if (type != PaymentType.PER_LESSON) {
                        strays.add(new StrayLessonCharge(
                            t.getId(), t.getType(), amount, t.getCreatedAt(), t.getNote()));
                    }
                }
                default -> {
                    if (CARRIED_TYPES.contains(t.getType()) && !isRepairAdjustment(t)) {
                        carriedLedger = carriedLedger.add(amount);
                    }
                }
            }
        }

        BigDecimal fee = PaymentScheduleService.resolveMonthlyFee(sg);
        BigDecimal cashIn = BigDecimal.ZERO;
        BigDecimal periodCost = BigDecimal.ZERO;
        List<MissingPeriodCharge> missing = new ArrayList<>();
        List<WrongCredit> wrongCredits = new ArrayList<>();

        for (Payment p : payments) {
            BigDecimal cash = resolveCashAmount(p);
            cashIn = cashIn.add(cash);

            BigDecimal recorded = creditByPayment.get(p.getId());
            if (recorded == null || recorded.compareTo(cash) != 0) {
                wrongCredits.add(new WrongCredit(
                    p.getId(), p.getReceiptNumber(), cash,
                    recorded != null ? recorded : BigDecimal.ZERO));
            }

            if (type != PaymentType.MONTHLY) {
                continue;
            }
            int months = PeriodChargeFormula.months(resolveGross(p), fee);
            BigDecimal cost = PeriodChargeFormula.debit(months, resolveDiscount(p), fee);
            if (cost.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            periodCost = periodCost.add(cost);
            if (!chargedPayments.contains(p.getId())) {
                missing.add(new MissingPeriodCharge(
                    p.getId(), p.getReceiptNumber(),
                    p.getPeriodStart(), p.getPeriodEnd(), months, cost.negate()));
            }
        }

        BigDecimal lessonCost = BigDecimal.ZERO;
        if (type == PaymentType.PER_LESSON) {
            lessonCost = computeLessonCost(sg);
        }

        List<UnlinkedPayment> unlinked = findUnlinkedPayments(sg);

        BigDecimal stored = nz(sg.getBalance());
        BigDecimal expected = cashIn
            .subtract(periodCost)
            .subtract(lessonCost)
            .add(carriedLedger);

        return new Expectation(
            sg.getId(),
            sg.getStudent() != null ? sg.getStudent().getId() : null,
            studentName(sg),
            sg.getGroup() != null ? sg.getGroup().getGroupName() : null,
            type,
            cashIn,
            periodCost,
            lessonCost,
            carriedLedger,
            ledgerSum,
            stored,
            expected,
            expected.subtract(stored),
            missing,
            wrongCredits,
            strays,
            unlinked);
    }

    private List<UnlinkedPayment> findUnlinkedPayments(StudentGroup sg) {
        if (sg.getStudent() == null || sg.getGroup() == null) {
            return List.of();
        }
        return paymentRepository
            .findUnlinkedByStudentAndGroup(
                sg.getStudent().getId(), sg.getGroup().getId(), PaymentStatus.PAID)
            .stream()
            .map(p -> new UnlinkedPayment(
                p.getId(), p.getReceiptNumber(), p.getPaymentDate(), resolveCashAmount(p)))
            .toList();
    }

    private BigDecimal computeLessonCost(StudentGroup sg) {
        if (sg.getStudent() == null || sg.getGroup() == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal lessonPrice = PaymentScheduleService.resolveLessonPrice(sg);
        if (lessonPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        LocalDate from = sg.getPaymentStartDate() != null
            ? sg.getPaymentStartDate()
            : (sg.getJoinDate() != null ? sg.getJoinDate() : LocalDate.EPOCH);
        long billable = attendanceRepository.countByStudentAndGroupAndStatusesSince(
            sg.getStudent().getId(), sg.getGroup().getId(), BILLABLE_STATUSES, from);
        return lessonPrice.multiply(BigDecimal.valueOf(billable));
    }

    private static boolean isRepairAdjustment(BalanceTransaction t) {
        return t.getType() == BalanceTransactionType.MANUAL_ADJUST
            && t.getNote() != null
            && t.getNote().startsWith(REPAIR_NOTE_PREFIX);
    }

    /**
     * Kassaga tushgan real pul. Eski satrlarda cash_amount NULL —
     * o'shanda amount aynan naqd summani bildirgan.
     */
    private static BigDecimal resolveCashAmount(Payment p) {
        return p.getCashAmount() != null ? p.getCashAmount() : nz(p.getAmount());
    }

    /**
     * Gross — chegirma va balansdan qoplangan qism ayrilmagan to'liq summa.
     * Eski satrlarda payable_amount NULL: o'shanda amount kassaga tushgan summani
     * bildirgan, ya'ni gross = amount + balanceUsed.
     */
    private static BigDecimal resolveGross(Payment p) {
        if (p.getPayableAmount() != null) {
            return nz(p.getAmount());
        }
        return nz(p.getAmount()).add(nz(p.getBalanceUsed()));
    }

    /**
     * Ledger yozilgan paytdagi chegirma.
     *
     * <p>DIQQAT: {@code p.discountAmount} ni o'qib bo'lmaydi — bonus qo'llanganda
     * u PERIOD_CHARGE yozilgandan KEYIN {@code discount + bonusDiscount} ga
     * yangilanadi ({@code PaymentService.createPayment}). Ishonchli manba —
     * {@code gross - payableAmount}, chunki {@code payableAmount} bir marta
     * yoziladi va keyin tegilmaydi.
     */
    private static BigDecimal resolveDiscount(Payment p) {
        if (p.getPayableAmount() == null) {
            return BigDecimal.ZERO;
        }
        return nz(p.getAmount()).subtract(p.getPayableAmount()).max(BigDecimal.ZERO);
    }

    private static String studentName(StudentGroup sg) {
        if (sg.getStudent() == null) {
            return null;
        }
        return ((sg.getStudent().getFirstName() != null ? sg.getStudent().getFirstName() : "")
            + " "
            + (sg.getStudent().getLastName() != null ? sg.getStudent().getLastName() : "")).trim();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
