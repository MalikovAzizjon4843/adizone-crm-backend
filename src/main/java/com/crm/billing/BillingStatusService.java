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
 * B < 0 && days < grace → PENDING
 * B < 0 && days ≥ grace → OVERDUE      // grace standarti 0: muddat kuni to'lanmagan — qarzdor (§14.1, R1)
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
    private final LessonProrationService prorationService;

    public LocalDate today() {
        return LocalDate.now(billingClock);
    }

    public int graceDays() {
        return properties.getGraceDays();
    }

    /**
     * OVERDUE ⇔ {@code debtSince < overdueBefore(today)}, ya'ni {@code debtSince ≤ today − grace}
     * ({@code today − debtSince ≥ grace}). grace = 0 da {@code today + 1}: bugun yoki undan oldin muddati kelgan qarz.
     */
    public LocalDate overdueBefore(LocalDate today) {
        return today.minusDays(properties.getGraceDays()).plusDays(1);
    }

    public PaymentStatus statusOf(BigDecimal balance, LocalDate debtSince, LocalDate today) {
        if (balance == null || balance.signum() >= 0 || debtSince == null) {
            return PaymentStatus.PAID;
        }
        long days = ChronoUnit.DAYS.between(debtSince, today);
        return days >= properties.getGraceDays() ? PaymentStatus.OVERDUE : PaymentStatus.PENDING;
    }

    /** Saqlangan snapshot ustida — {@link #overdue(LocalDate)} bilan bir xil shart. */
    public boolean isOverdue(StudentGroup sg, LocalDate today) {
        return statusOf(sg.getBalance(), sg.getDebtSince(), today) == PaymentStatus.OVERDUE;
    }

    public boolean isPending(StudentGroup sg, LocalDate today) {
        return statusOf(sg.getBalance(), sg.getDebtSince(), today) == PaymentStatus.PENDING;
    }

    /** SQL: {@code balance < 0 AND debt_since ≤ today − grace}. */
    public Specification<StudentGroup> overdue(LocalDate today) {
        LocalDate before = overdueBefore(today);
        return (root, query, cb) -> cb.and(
            cb.lessThan(root.get("balance"), BigDecimal.ZERO),
            cb.isNotNull(root.get("debtSince")),
            cb.lessThan(root.get("debtSince"), before));
    }

    /** SQL: {@code balance < 0 AND debt_since > today − grace} (faqat grace > 0 da bo'sh emas). */
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
        return snapshotWith(sg, plannedLines, plannedPeriodStarts, today, groupEnd(sg));
    }

    /**
     * Xuddi shu hisob, lekin guruh tugash sanasi o'rniga {@code groupEnd} (R3): guruh {@code end_date}
     * o'zgarishi ta'sirini yozmasdan ko'rish ({@code GroupEndDateService.impact}).
     */
    public BillingSnapshot snapshotWith(StudentGroup sg, List<FifoDebt.Line> plannedLines,
                                        Collection<LocalDate> plannedPeriodStarts, LocalDate today,
                                        LocalDate groupEnd) {
        List<FifoDebt.Line> lines = new ArrayList<>();
        if (sg.getId() != null) {
            for (BalanceTransaction t : transactionRepository.findLedgerForFifo(sg.getId())) {
                lines.add(new FifoDebt.Line(t.getId(), t.getAmount(), t.getEffectiveDate(), t.getRelatedTxId(),
                    PeriodCoverageService.isNeutral(t)));
            }
        }
        lines.addAll(plannedLines);
        FifoDebt.Result fifo = FifoDebt.compute(lines);

        // Yozilgan davrlar — saqlangan chegaralar bilan; rejadagilari — zanjir oxiri panjaradan (§14.7)
        List<BillingCalendar.Span> billed = new ArrayList<>();
        if (sg.getId() != null) {
            for (BillingPeriod p : periodRepository.findByStudentGroupIdOrderByPeriodStartAsc(sg.getId())) {
                billed.add(new BillingCalendar.Span(p.getPeriodStart(), p.getPeriodEnd()));
            }
        }
        LocalDate anchor = sg.getPaymentStartDate();
        for (LocalDate start : plannedPeriodStarts) {
            if (anchor != null && !start.isBefore(anchor)) {
                billed.add(new BillingCalendar.Span(start, BillingCalendar.endOf(anchor, start)));
            }
        }

        BigDecimal balance = fifo.balance();
        PaymentStatus status = displayStatus(sg, balance, fifo.debtSince(), today);
        BigDecimal fee = isPerLesson(sg) ? EnrollmentPricing.effectiveLessonPrice(sg)
            : EnrollmentPricing.effectiveMonthlyFee(sg);
        NextPayment next = nextPayment(sg, balance, fifo.debtSince(), fee, billed, today, groupEnd);
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

    /** {@code lessons / lessonPrice} — sana oxirgi (darslar bo'yicha hisoblanadigan) davrga tushsa. */
    private record NextPayment(LocalDate date, BigDecimal amount, Integer lessons, BigDecimal lessonPrice) {
        static final NextPayment NONE = new NextPayment(null, null, null, null);

        NextPayment(LocalDate date, BigDecimal amount) {
            this(date, amount, null, null);
        }
    }

    /**
     * §4.3. Trial, muzlatilgan yoki yopilgan SG: null. Qarz bo'lsa — "to'lash kerak
     * edi": {@code debtSince / −B}. Aks holda B ning necha davrni oldindan
     * qoplashi va keyingi davr boshi.
     */
    private NextPayment nextPayment(StudentGroup sg, BigDecimal balance, LocalDate debtSince,
                                    BigDecimal fee, Collection<BillingCalendar.Span> billed, LocalDate today,
                                    LocalDate groupEnd) {
        if (!isBillingOpen(sg)) {
            return NextPayment.NONE;
        }
        if (balance.signum() < 0) {
            return new NextPayment(debtSince, balance.negate());
        }
        if (fee == null || fee.signum() <= 0) {
            return NextPayment.NONE;
        }
        if (isPerLesson(sg)) {
            long k = Money.floorDiv(balance, fee);
            BigDecimal amount = fee.subtract(balance.subtract(fee.multiply(BigDecimal.valueOf(k))));
            LocalDate lesson = nthUpcomingLesson(sg, (int) Math.min(k + 1, 1000), today);
            return lesson != null ? capByGroupEnd(new NextPayment(lesson, amount), sg, groupEnd) : NextPayment.NONE;
        }

        LocalDate anchor = sg.getPaymentStartDate();
        if (anchor == null) {
            return NextPayment.NONE;
        }
        // Hali yozilmagan birinchi davrdan (zanjir, §14.7) boshlab B oldindan qoplagan davrlar o'tkaziladi; har davr
        // summasi — AccrualCalculator.charge (oxirgi davr darslar bo'yicha). R3: davr boshi ≥ end_date — to'lov yo'q.
        AccrualCalculator.State state = AccrualCalculator.State.of(sg).withGroupEndDate(groupEnd);
        AccrualCalculator.Proration proration = prorationService.forEnrollment(sg);
        BigDecimal remaining = balance;
        LocalDate start = BillingCalendar.firstUnbilledStart(anchor, billed);
        for (int i = 0; i <= 10_000; i++) {
            if (groupEnd != null && !start.isBefore(groupEnd)) {
                return NextPayment.NONE;
            }
            AccrualCalculator.DueCharge charge = AccrualCalculator.charge(state, start,
                BillingCalendar.endOf(anchor, start), proration);
            if (remaining.compareTo(charge.amount()) < 0) {
                return new NextPayment(start, charge.amount().subtract(remaining),
                    charge.proratedLessons(), charge.lessonPrice());
            }
            remaining = remaining.subtract(charge.amount());
            start = BillingCalendar.following(anchor, start);
        }
        return NextPayment.NONE;
    }

    /**
     * R3 (§14.3): guruh tugagach to'lov kutilmaydi — MONTHLY davr boshi ≥ {@code end_date} (shu kuni boshlanadigan
     * davr ham ochilmaydi, {@link AccrualCalculator#isAccruable}); PER_LESSON — {@code end_date} dan keyingi dars.
     */
    private static NextPayment capByGroupEnd(StudentGroup sg, NextPayment np) {
        return capByGroupEnd(np, sg, groupEnd(sg));
    }

    private static NextPayment capByGroupEnd(NextPayment np, StudentGroup sg, LocalDate end) {
        if (end == null || np.date() == null) {
            return np;
        }
        boolean after = isPerLesson(sg) ? np.date().isAfter(end) : !np.date().isBefore(end);
        return after ? NextPayment.NONE : np;
    }

    private static LocalDate groupEnd(StudentGroup sg) {
        return sg.getGroup() != null ? sg.getGroup().getEndDate() : null;
    }

    /**
     * Kutilayotgan to'lov (R2): sana va summa; davr oxirgi bo'lsa (guruh shu davrda tugaydi) — {@code proratedLessons}
     * va {@code lessonPrice} ("7 dars × 250 000"), aks holda null.
     */
    public record Upcoming(LocalDate date, BigDecimal amount, Integer proratedLessons, BigDecimal lessonPrice) {
    }

    /**
     * R2 (§14.2) — kutilayotgan to'lov, yozilma uchun BITTA, sanasi bugundan keyin; bo'lmasa null.
     * <ul>
     *   <li>qarzsiz ({@code B ≥ 0}) — §4.3 bilan bir xil (oldindan qoplangan davrlardan keyingi birinchi davr);</li>
     *   <li>qarzdor — bugundan keyingi birinchi hali ochilmagan davr, summa = joriy narx {@code c} (qarz alohida);
     *       PER_LESSON — ertangi birinchi jadvaldagi dars, summa = {@code l};</li>
     *   <li>guruh tugashidan keyin — yo'q (R3).</li>
     * </ul>
     *
     * @param billed shu SG ning yozilgan davrlari — saqlangan chegaralar (chaqiruvchi batch'da o'qiydi)
     */
    public Upcoming upcoming(StudentGroup sg, Collection<BillingCalendar.Span> billed, LocalDate today) {
        if (!isBillingOpen(sg)) {
            return null;
        }
        BigDecimal balance = Money.nz(sg.getBalance());
        BigDecimal fee = isPerLesson(sg) ? EnrollmentPricing.effectiveLessonPrice(sg)
            : EnrollmentPricing.effectiveMonthlyFee(sg);
        NextPayment np;
        if (balance.signum() >= 0) {
            np = nextPayment(sg, balance, null, fee, billed, today, groupEnd(sg));
        } else if (fee == null || fee.signum() <= 0) {
            np = NextPayment.NONE;
        } else if (isPerLesson(sg)) {
            LocalDate lesson = nthUpcomingLesson(sg, 1, today);
            np = lesson != null ? capByGroupEnd(sg, new NextPayment(lesson, fee)) : NextPayment.NONE;
        } else if (sg.getPaymentStartDate() == null) {
            np = NextPayment.NONE;
        } else {
            LocalDate anchor = sg.getPaymentStartDate();
            LocalDate start = BillingCalendar.firstUnbilledStart(anchor, billed);
            for (int i = 0; i < 10_000 && !start.isAfter(today); i++) {
                start = BillingCalendar.following(anchor, start);
            }
            LocalDate end = groupEnd(sg);
            if (end != null && !start.isBefore(end)) {
                np = NextPayment.NONE;
            } else {
                AccrualCalculator.DueCharge charge = AccrualCalculator.charge(AccrualCalculator.State.of(sg), start,
                    BillingCalendar.endOf(anchor, start), prorationService.forEnrollment(sg));
                np = charge.amount().signum() > 0
                    ? new NextPayment(start, charge.amount(), charge.proratedLessons(), charge.lessonPrice())
                    : NextPayment.NONE;
            }
        }
        if (np.date() == null || !np.date().isAfter(today)) {
            return null;
        }
        return new Upcoming(np.date(), np.amount(), np.lessons(), np.lessonPrice());
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
