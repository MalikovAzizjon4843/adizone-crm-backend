package com.crm.billing;

import com.crm.entity.BalanceTransaction;
import com.crm.entity.BillingPeriod;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.PaymentStatus;
import com.crm.entity.enums.PaymentType;
import com.crm.repository.BalanceTransactionRepository;
import com.crm.repository.BillingPeriodRepository;
import com.crm.service.GroupScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * YAGONA holat va qarz ta'rifi — docs/design/billing-v2.md §4.
 *
 * <p>Uchta qiymat: {@code balance}, {@code debt_since}, {@code grace}. Java tomoni
 * ({@link #statusOf}, {@link #isOverdue}) va SQL tomoni ({@link #overdue}, {@link #pending})
 * aynan shulardan foydalanadi — ekvivalentlik testi bor (§12 T2.4).
 *
 * <pre>
 * days = today − debtSince
 * B ≥ 0                 → PAID
 * B < 0 && days ≤ grace → PENDING
 * B < 0 && days > grace → OVERDUE
 * </pre>
 * Qarzdor (§4.5) — kamida bitta SG si OVERDUE bo'lgan o'quvchi.
 */
@Service
@RequiredArgsConstructor
public class BillingStatusService {

    private final BillingProperties properties;
    private final Clock billingClock;
    private final BalanceTransactionRepository transactionRepository;
    private final BillingPeriodRepository periodRepository;
    private final GroupScheduleService groupScheduleService;

    public LocalDate today() {
        return LocalDate.now(billingClock);
    }

    public int graceDays() {
        return properties.getGraceDays();
    }

    /** OVERDUE ⇔ {@code debtSince < overdueBefore(today)} (ya'ni {@code today − debtSince > grace}). */
    public LocalDate overdueBefore(LocalDate today) {
        return today.minusDays(properties.getGraceDays());
    }

    public PaymentStatus statusOf(BigDecimal balance, LocalDate debtSince, LocalDate today) {
        if (balance == null || balance.signum() >= 0 || debtSince == null) {
            return PaymentStatus.PAID;
        }
        long days = ChronoUnit.DAYS.between(debtSince, today);
        return days > properties.getGraceDays() ? PaymentStatus.OVERDUE : PaymentStatus.PENDING;
    }

    /** Saqlangan snapshot ustida — {@link #overdue(LocalDate)} bilan bir xil shart. */
    public boolean isOverdue(StudentGroup sg, LocalDate today) {
        return statusOf(sg.getBalance(), sg.getDebtSince(), today) == PaymentStatus.OVERDUE;
    }

    public boolean isPending(StudentGroup sg, LocalDate today) {
        return statusOf(sg.getBalance(), sg.getDebtSince(), today) == PaymentStatus.PENDING;
    }

    /** SQL: {@code balance < 0 AND debt_since < today − grace}. */
    public Specification<StudentGroup> overdue(LocalDate today) {
        LocalDate before = overdueBefore(today);
        return (root, query, cb) -> cb.and(
            cb.lessThan(root.get("balance"), BigDecimal.ZERO),
            cb.isNotNull(root.get("debtSince")),
            cb.lessThan(root.get("debtSince"), before));
    }

    /** SQL: {@code balance < 0 AND debt_since ≥ today − grace}. */
    public Specification<StudentGroup> pending(LocalDate today) {
        LocalDate before = overdueBefore(today);
        return (root, query, cb) -> cb.and(
            cb.lessThan(root.get("balance"), BigDecimal.ZERO),
            cb.isNotNull(root.get("debtSince")),
            cb.greaterThanOrEqualTo(root.get("debtSince"), before));
    }

    /**
     * Ochiq yozilma: faol yoki muzlatilgan (muzlatish SG ni yopmaydi, §6.7).
     * Qarzdorlar ro'yxatining default doirasi shular (§13 #4).
     */
    public static boolean isOpen(StudentGroup sg) {
        return Boolean.TRUE.equals(sg.getIsActive()) || sg.getFrozenFrom() != null;
    }

    /**
     * Hisoblanadigan (trial/frozen/yopilgan emas, guruh ACTIVE yoki FORMING — §13 #14) —
     * nextPaymentDate va kutilayotgan to'lovlar shular uchun.
     */
    public static boolean isBillingOpen(StudentGroup sg) {
        com.crm.entity.enums.GroupStatus gs = sg.getGroup() != null ? sg.getGroup().getStatus() : null;
        return Boolean.TRUE.equals(sg.getIsActive())
            && sg.getFrozenFrom() == null
            && !Boolean.TRUE.equals(sg.getIsTrial())
            && (gs == null || gs == com.crm.entity.enums.GroupStatus.ACTIVE
                || gs == com.crm.entity.enums.GroupStatus.FORMING);
    }

    // ── Snapshot ───────────────────────────────────────────────────────

    /** Bitta SG uchun to'liq holat (FIFO bilan), saqlangan ledger bo'yicha. */
    public BillingSnapshot snapshot(StudentGroup sg, LocalDate today) {
        return snapshotWith(sg, List.of(), List.of(), today);
    }

    /**
     * Ledger + rejadagi (hali yozilmagan) qatorlar ustida — to'lov preview'i shu
     * bilan "keyingi holat"ni hisoblaydi (§5.2 qadam 6), create esa yozgandan
     * keyin {@link #snapshot}. Formula bitta.
     *
     * @param plannedLines       hali yozilmagan ledger qatorlari (id = null)
     * @param plannedPeriodStarts shu qatorlar bilan yoziladigan davr boshlari
     */
    public BillingSnapshot snapshotWith(StudentGroup sg, List<FifoDebt.Line> plannedLines,
                                        Collection<LocalDate> plannedPeriodStarts, LocalDate today) {
        List<FifoDebt.Line> lines = new ArrayList<>();
        if (sg.getId() != null) {
            for (BalanceTransaction t : transactionRepository.findLedgerForFifo(sg.getId())) {
                lines.add(new FifoDebt.Line(t.getId(), t.getAmount(), t.getEffectiveDate(), t.getRelatedTxId()));
            }
        }
        lines.addAll(plannedLines);
        FifoDebt.Result fifo = FifoDebt.compute(lines);

        Set<LocalDate> billedStarts = new HashSet<>(plannedPeriodStarts);
        if (sg.getId() != null) {
            for (BillingPeriod p : periodRepository.findByStudentGroupIdOrderByPeriodStartAsc(sg.getId())) {
                billedStarts.add(p.getPeriodStart());
            }
        }

        BigDecimal balance = fifo.balance();
        PaymentStatus status = displayStatus(sg, balance, fifo.debtSince(), today);
        BigDecimal fee = isPerLesson(sg) ? EnrollmentPricing.effectiveLessonPrice(sg)
            : EnrollmentPricing.effectiveMonthlyFee(sg);
        NextPayment next = nextPayment(sg, balance, fifo.debtSince(), fee, billedStarts, today);
        return new BillingSnapshot(balance, fifo.debt(), fifo.debtSince(), status,
            next.date(), next.amount(), fee);
    }

    /** §4.2 jadvali — yuqoridan pastga birinchi mos. Qarz har doim ustun. */
    public PaymentStatus displayStatus(StudentGroup sg, BigDecimal balance, LocalDate debtSince, LocalDate today) {
        PaymentStatus money = statusOf(balance, debtSince, today);
        if (money != PaymentStatus.PAID) {
            return money;
        }
        if (sg.getFrozenFrom() != null) {
            return PaymentStatus.FROZEN;
        }
        if (Boolean.TRUE.equals(sg.getIsTrial())) {
            return PaymentStatus.TRIAL;
        }
        return PaymentStatus.PAID;
    }

    private record NextPayment(LocalDate date, BigDecimal amount) {
        static final NextPayment NONE = new NextPayment(null, null);
    }

    /**
     * §4.3. Trial, muzlatilgan yoki yopilgan SG: null. Qarz bo'lsa — "to'lash kerak
     * edi": {@code debtSince / −B}. Aks holda B ning necha davrni oldindan
     * qoplashi va keyingi davr boshi.
     */
    private NextPayment nextPayment(StudentGroup sg, BigDecimal balance, LocalDate debtSince,
                                    BigDecimal fee, Set<LocalDate> billedStarts, LocalDate today) {
        if (!isBillingOpen(sg)) {
            return NextPayment.NONE;
        }
        if (balance.signum() < 0) {
            return new NextPayment(debtSince, balance.negate());
        }
        if (fee == null || fee.signum() <= 0) {
            return NextPayment.NONE;
        }
        long k = Money.floorDiv(balance, fee);
        BigDecimal amount = fee.subtract(balance.subtract(fee.multiply(BigDecimal.valueOf(k))));

        if (isPerLesson(sg)) {
            LocalDate lesson = nthUpcomingLesson(sg, (int) Math.min(k + 1, 1000), today);
            return lesson != null ? new NextPayment(lesson, amount) : NextPayment.NONE;
        }

        LocalDate anchor = sg.getPaymentStartDate();
        if (anchor == null) {
            return NextPayment.NONE;
        }
        int n = 0;
        while (billedStarts.contains(BillingCalendar.start(anchor, n))) {
            n++;
        }
        long target = n + k;
        if (target > 10_000) {
            return NextPayment.NONE;
        }
        return new NextPayment(BillingCalendar.start(anchor, (int) target), amount);
    }

    /** Jadval bo'yicha ertadan boshlab {@code n}-chi dars sanasi; jadval yo'q — null (§13 #28). */
    private LocalDate nthUpcomingLesson(StudentGroup sg, int n, LocalDate today) {
        if (sg.getGroup() == null) {
            return null;
        }
        Set<DayOfWeek> days = groupScheduleService.lessonWeekdays(sg.getGroup().getId());
        if (days.isEmpty()) {
            return null;
        }
        LocalDate cursor = today.plusDays(1);
        int left = n;
        LocalDate limit = today.plusYears(3);
        while (!cursor.isAfter(limit)) {
            if (days.contains(cursor.getDayOfWeek()) && --left == 0) {
                return cursor;
            }
            cursor = cursor.plusDays(1);
        }
        return null;
    }

    private static boolean isPerLesson(StudentGroup sg) {
        return sg.getPaymentType() == PaymentType.PER_LESSON;
    }
}
