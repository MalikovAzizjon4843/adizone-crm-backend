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
    }

    /** Hisoblanishi kerak bo'lgan bitta davr. {@code amount} — {@code c}, 0 bo'lishi mumkin (d = 100). */
    public record DueCharge(
        LocalDate periodStart,
        LocalDate periodEnd,
        BigDecimal fee,
        BigDecimal discountPercentage,
        BigDecimal amount) {
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
     *   && (group.endDate == null || date < group.endDate)  (R3, §14.3 — oxirgi qisman davr to'liq narx bilan)
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
        if (s.anchor() == null) {
            return Result.empty();
        }
        BigDecimal d = EnrollmentPricing.validDiscount(s.discountPercentage());
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
                charges.add(new DueCharge(start, end, s.fee(), d, Money.discounted(s.fee(), d)));
                k++;
            }
            start = end.plusDays(1);
        }
    }
}
