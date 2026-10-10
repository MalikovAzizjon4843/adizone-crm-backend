package com.crm.billing;

import com.crm.entity.StudentGroup;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.PaymentType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Accrual hisobi — SOF funksiya (DB yozmaydi), docs/design/billing-v2.md §3.2, §3.8.
 * {@code AccrualService.accrueUpTo} (yozadi), to'lov preview'i (§5.2 qadam 3) va
 * migratsiya dry-run'i (§9) aynan shuni ishlatadi — natijalar ajralib ketmaydi.
 */
public final class AccrualCalculator {

    private AccrualCalculator() {
    }

    /** Yozilmaning accrual uchun kerakli holati (entity'dan mustaqil). */
    public record State(
        PaymentType paymentType,
        boolean trial,
        boolean active,
        LocalDate anchor,
        LocalDate frozenFrom,
        LocalDate leaveDate,
        GroupStatus groupStatus,
        BigDecimal fee,
        BigDecimal discountPercentage,
        /** Guruh tugash sanasi — shu kuni yoki keyin boshlanadigan davr ochilmaydi (R3, §14.3). null — cheklovsiz. */
        LocalDate groupEndDate) {

        /** Guruh tugash sanasisiz (eski chaqiruvlar, testlar). */
        public State(PaymentType paymentType, boolean trial, boolean active, LocalDate anchor, LocalDate frozenFrom,
                     LocalDate leaveDate, GroupStatus groupStatus, BigDecimal fee, BigDecimal discountPercentage) {
            this(paymentType, trial, active, anchor, frozenFrom, leaveDate, groupStatus, fee, discountPercentage, null);
        }

        public static State of(StudentGroup sg) {
            return new State(
                sg.getPaymentType() != null ? sg.getPaymentType() : PaymentType.MONTHLY,
                Boolean.TRUE.equals(sg.getIsTrial()),
                Boolean.TRUE.equals(sg.getIsActive()),
                sg.getPaymentStartDate(),
                sg.getFrozenFrom(),
                sg.getLeaveDate(),
                sg.getGroup() != null ? sg.getGroup().getStatus() : null,
                EnrollmentPricing.monthlyFee(sg),
                EnrollmentPricing.discount(sg),
                sg.getGroup() != null ? sg.getGroup().getEndDate() : null);
        }

        /** Xuddi shu holat, boshqa guruh tugash sanasi bilan (null — cheklovsiz). */
        public State withGroupEndDate(LocalDate end) {
            return new State(paymentType, trial, active, anchor, frozenFrom, leaveDate, groupStatus, fee,
                discountPercentage, end);
        }
    }

    /**
     * Hisoblanishi kerak bo'lgan bitta davr. {@code amount} — {@code c}, 0 bo'lishi mumkin (d = 100); oxirgi davrda
     * (guruh shu davr ichida tugaydi) — darslar bo'yicha, {@code proratedLessons} va {@code lessonPrice} bilan
     * (to'liq davrda ikkalasi null).
     */
    public record DueCharge(
        LocalDate periodStart,
        LocalDate periodEnd,
        BigDecimal fee,
        BigDecimal discountPercentage,
        BigDecimal amount,
        Integer proratedLessons,
        BigDecimal lessonPrice) {

        /** To'liq davr. */
        public DueCharge(LocalDate periodStart, LocalDate periodEnd, BigDecimal fee, BigDecimal discountPercentage,
                         BigDecimal amount) {
            this(periodStart, periodEnd, fee, discountPercentage, amount, null, null);
        }

        public boolean prorated() {
            return proratedLessons != null;
        }
    }

    /**
     * Oxirgi davr darslari (buyurtmachi qoidasi 2026-10-10): oyiga darslar soni (sozlama
     * {@code billing.lessons_per_month}) va guruh jadvali bo'yicha darslar. {@code LessonProrationService} beradi.
     */
    public interface Proration {

        int lessonsPerMonth();

        /** {@code [from, to]} (ikkala chet kiradi) dagi darslar; guruh jadvalsiz — null (oxirgi davr ham to'liq c). */
        Integer lessons(LocalDate from, LocalDate to);

        /** Jadval ma'lumotisiz (sof hisob testlari): oxirgi davr ham to'liq {@code c}. */
        Proration NONE = new Proration() {
            @Override
            public int lessonsPerMonth() {
                return 12;
            }

            @Override
            public Integer lessons(LocalDate from, LocalDate to) {
                return null;
            }
        };
    }

    /** Oxirgi davr: {@code period_start ≤ end_date < period_end} ({@code periodEnd} — davrning oxirgi kuni). */
    public static boolean isLastPeriod(LocalDate groupEnd, LocalDate periodStart, LocalDate periodEnd) {
        return groupEnd != null && periodStart != null && periodEnd != null
            && !periodStart.isAfter(groupEnd) && groupEnd.isBefore(periodEnd);
    }

    /**
     * Bitta MONTHLY davr summasi — YAGONA formula (accrual, to'lov preview'i, kutilayotgan, keyingi to'lov, end_date
     * preview'i, yozilgan davrni qayta hisoblash). {@code fee} / {@code discount} — davr narxi (yozilgan davrda —
     * o'sha paytdagisi). {@code c = discounted(fee, d)}; oxirgi davrda
     * {@code min(c, uzs(c / lessonsPerMonth) × darslar[period_start, end_date])} ({@link EnrollmentPricing});
     * jadvalsiz guruh yoki darslar bo'yicha summa {@code c} dan kam bo'lmasa — to'liq {@code c}.
     */
    public static DueCharge periodCharge(LocalDate start, LocalDate end, BigDecimal fee, BigDecimal discount,
                                         LocalDate groupEnd, Proration proration) {
        BigDecimal d = EnrollmentPricing.validDiscount(discount);
        BigDecimal c = Money.discounted(fee, d);
        if (isLastPeriod(groupEnd, start, end)) {
            Integer lessons = proration.lessons(start, groupEnd);
            if (lessons != null) {
                BigDecimal lessonPrice = EnrollmentPricing.monthlyLessonPrice(c, proration.lessonsPerMonth());
                BigDecimal amount = EnrollmentPricing.lastPeriodAmount(c, lessonPrice, lessons);
                if (amount.compareTo(c) < 0) {
                    return new DueCharge(start, end, fee, d, amount, lessons, lessonPrice);
                }
            }
        }
        return new DueCharge(start, end, fee, d, c);
    }

    /** {@link #periodCharge} — yozilmaning joriy narxi va guruh tugash sanasi bilan. */
    public static DueCharge charge(State s, LocalDate start, LocalDate end, Proration proration) {
        return periodCharge(start, end, s.fee(), s.discountPercentage(), s.groupEndDate(), proration);
    }

    public record Result(List<DueCharge> charges, boolean catchUpLimitReached) {
        public static Result empty() {
            return new Result(List.of(), false);
        }
    }

    /**
     * <pre>
     * isAccruable(sg, date) =
     *      MONTHLY && !trial
     *   && anchor != null && anchor ≤ date
     *   && frozenFrom == null
     *   && (leaveDate == null || date ≤ leaveDate)
     *   && group.status ∈ {ACTIVE, FORMING}                (§13 #14)
     *   && (group.endDate == null || date < group.endDate)  (R3, §14.3; oxirgi davr summasi — darslar bo'yicha, {@link #periodCharge})
     *   && fee > 0
     * </pre>
     * Qo'shimcha: nofaol, lekin {@code leaveDate} siz eski yozilma (nomuvofiq qator)
     * hisoblanmaydi — aks holda u cheksiz davr olardi.
     */
    public static boolean isAccruable(State s, LocalDate date) {
        if (s.paymentType() != PaymentType.MONTHLY || s.trial()) {
            return false;
        }
        if (s.anchor() == null || date.isBefore(s.anchor())) {
            return false;
        }
        if (s.frozenFrom() != null) {
            return false;
        }
        if (s.leaveDate() != null) {
            if (date.isAfter(s.leaveDate())) {
                return false;
            }
        } else if (!s.active()) {
            return false;
        }
        if (s.groupStatus() != GroupStatus.ACTIVE && s.groupStatus() != GroupStatus.FORMING) {
            return false;
        }
        if (s.groupEndDate() != null && !date.isBefore(s.groupEndDate())) {
            return false;
        }
        return s.fee() != null && s.fee().signum() > 0;
    }

    /**
     * {@code asOf} gacha boshlangan, hali hisoblanmagan davrlar (ko'pi bilan
     * {@code maxCatchUp} ta). {@code existingStarts} — shu SG ning
     * {@code billing_periods.period_start} lari (oldingi langardagilari ham).
     */
    public static Result dueCharges(State s, Collection<LocalDate> existingStarts,
                                    LocalDate asOf, int maxCatchUp) {
        if (s.anchor() == null) {
            return Result.empty();
        }
        List<BillingCalendar.Span> spans = existingStarts.stream()
            .filter(st -> !st.isBefore(s.anchor()))
            .map(st -> new BillingCalendar.Span(st, BillingCalendar.endOf(s.anchor(), st)))
            .toList();
        return dueChargesAfter(s, spans, asOf, maxCatchUp);
    }

    /**
     * Davr zanjiri bo'yicha (§14.7, {@link BillingCalendar#firstUnbilledStart}): keyingi davr — oxirgi yozilgan davr
     * oxiridan keyingi kun, oxiri — langar kuni panjarasi bo'yicha. {@code billed} — shu SG ning yozilgan davrlari
     * (saqlangan {@code period_start / period_end}).
     */
    public static Result dueChargesAfter(State s, Collection<BillingCalendar.Span> billed,
                                         LocalDate asOf, int maxCatchUp) {
        return dueChargesAfter(s, billed, asOf, maxCatchUp, Proration.NONE);
    }

    /** {@link #dueChargesAfter(State, Collection, LocalDate, int)} — oxirgi davr darslar bo'yicha ({@code proration}). */
    public static Result dueChargesAfter(State s, Collection<BillingCalendar.Span> billed,
                                         LocalDate asOf, int maxCatchUp, Proration proration) {
        if (s.anchor() == null) {
            return Result.empty();
        }
        Set<LocalDate> existing = new HashSet<>();
        billed.forEach(b -> existing.add(b.start()));

        List<DueCharge> charges = new ArrayList<>();
        int k = 0;
        LocalDate start = BillingCalendar.firstUnbilledStart(s.anchor(), billed);
        while (true) {
            if (start.isAfter(asOf) || !isAccruable(s, start)) {
                return new Result(charges, false);
            }
            if (k >= maxCatchUp) {
                return new Result(charges, true);
            }
            LocalDate end = BillingCalendar.endOf(s.anchor(), start);
            if (!existing.contains(start)) {
                charges.add(charge(s, start, end, proration));
                k++;
            }
            start = end.plusDays(1);
        }
    }
}
