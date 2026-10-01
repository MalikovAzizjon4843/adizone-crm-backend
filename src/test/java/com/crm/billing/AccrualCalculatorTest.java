package com.crm.billing;

import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.PaymentType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** §3.2 isAccruable va §3.8 quvib yetish — sof funksiya. */
class AccrualCalculatorTest {

    private static final LocalDate ANCHOR = LocalDate.of(2026, 9, 15);

    private static AccrualCalculator.State state() {
        return new AccrualCalculator.State(PaymentType.MONTHLY, false, true, ANCHOR, null, null,
            GroupStatus.ACTIVE, BigDecimal.valueOf(700_000), BigDecimal.ZERO);
    }

    private static AccrualCalculator.Result due(AccrualCalculator.State s, LocalDate asOf, int max) {
        return AccrualCalculator.dueCharges(s, List.of(), asOf, max);
    }

    @Test
    void chargesEveryStartedPeriod_withEffectiveFeeAndEnds() {
        AccrualCalculator.Result r = due(state(), LocalDate.of(2026, 10, 17), 24);
        assertThat(r.charges()).extracting(AccrualCalculator.DueCharge::periodStart)
            .containsExactly(ANCHOR, LocalDate.of(2026, 10, 15));
        assertThat(r.charges().get(0).periodEnd()).isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(r.charges()).allSatisfy(c -> assertThat(c.amount()).isEqualByComparingTo("700000"));
        assertThat(r.catchUpLimitReached()).isFalse();
    }

    @Test
    void skipsExistingPeriods() {
        AccrualCalculator.Result r = AccrualCalculator.dueCharges(state(),
            List.of(ANCHOR), LocalDate.of(2026, 10, 15), 24);
        assertThat(r.charges()).extracting(AccrualCalculator.DueCharge::periodStart)
            .containsExactly(LocalDate.of(2026, 10, 15));
    }

    @Test
    void nothingBeforeAnchor() {
        assertThat(due(state(), LocalDate.of(2026, 9, 14), 24).charges()).isEmpty();
    }

    @Test
    void catchUpLimit() {
        AccrualCalculator.State s = new AccrualCalculator.State(PaymentType.MONTHLY, false, true,
            LocalDate.of(2026, 6, 15), null, null, GroupStatus.ACTIVE, BigDecimal.valueOf(700_000), BigDecimal.ZERO);
        AccrualCalculator.Result all = due(s, LocalDate.of(2026, 10, 1), 24);
        assertThat(all.charges()).hasSize(4);
        AccrualCalculator.Result limited = due(s, LocalDate.of(2026, 10, 1), 2);
        assertThat(limited.charges()).hasSize(2);
        assertThat(limited.catchUpLimitReached()).isTrue();
        // aynan chegarada — qoldiq yo'q bo'lsa belgi qo'yilmaydi
        assertThat(due(s, LocalDate.of(2026, 10, 1), 4).catchUpLimitReached()).isFalse();
    }

    @Test
    void notAccruable_trialFrozenPerLessonClosedGroupZeroFee() {
        LocalDate asOf = LocalDate.of(2026, 10, 20);
        BigDecimal fee = BigDecimal.valueOf(700_000);
        assertThat(due(new AccrualCalculator.State(PaymentType.MONTHLY, true, true, ANCHOR, null, null,
            GroupStatus.ACTIVE, fee, BigDecimal.ZERO), asOf, 24).charges()).as("trial").isEmpty();
        assertThat(due(new AccrualCalculator.State(PaymentType.MONTHLY, false, false, ANCHOR, ANCHOR, null,
            GroupStatus.ACTIVE, fee, BigDecimal.ZERO), asOf, 24).charges()).as("frozen").isEmpty();
        assertThat(due(new AccrualCalculator.State(PaymentType.PER_LESSON, false, true, ANCHOR, null, null,
            GroupStatus.ACTIVE, fee, BigDecimal.ZERO), asOf, 24).charges()).as("per lesson").isEmpty();
        assertThat(due(new AccrualCalculator.State(PaymentType.MONTHLY, false, true, ANCHOR, null, null,
            GroupStatus.COMPLETED, fee, BigDecimal.ZERO), asOf, 24).charges()).as("completed").isEmpty();
        assertThat(due(new AccrualCalculator.State(PaymentType.MONTHLY, false, true, ANCHOR, null, null,
            GroupStatus.CANCELLED, fee, BigDecimal.ZERO), asOf, 24).charges()).as("cancelled").isEmpty();
        assertThat(due(new AccrualCalculator.State(PaymentType.MONTHLY, false, true, ANCHOR, null, null,
            GroupStatus.FORMING, fee, BigDecimal.ZERO), asOf, 24).charges()).as("forming").hasSize(2);
        assertThat(due(new AccrualCalculator.State(PaymentType.MONTHLY, false, true, ANCHOR, null, null,
            GroupStatus.ACTIVE, BigDecimal.ZERO, BigDecimal.ZERO), asOf, 24).charges()).as("fee 0").isEmpty();
        // nofaol va leaveDate siz eski qator
        assertThat(due(new AccrualCalculator.State(PaymentType.MONTHLY, false, false, ANCHOR, null, null,
            GroupStatus.ACTIVE, fee, BigDecimal.ZERO), asOf, 24).charges()).as("inactive w/o leaveDate").isEmpty();
    }

    /** §6.10: leaveDate dan keyin boshlanadigan davrlar hisoblanmaydi. */
    @Test
    void stopsAfterLeaveDate() {
        AccrualCalculator.State s = new AccrualCalculator.State(PaymentType.MONTHLY, false, false, ANCHOR, null,
            LocalDate.of(2026, 10, 14), GroupStatus.ACTIVE, BigDecimal.valueOf(700_000), BigDecimal.ZERO);
        assertThat(due(s, LocalDate.of(2026, 12, 1), 24).charges()).hasSize(1);
    }

    @Test
    void fullDiscount_givesZeroAmountRow_invalidDiscountRejected() {
        AccrualCalculator.State free = new AccrualCalculator.State(PaymentType.MONTHLY, false, true, ANCHOR, null, null,
            GroupStatus.ACTIVE, BigDecimal.valueOf(500_000), BigDecimal.valueOf(100));
        AccrualCalculator.Result r = due(free, ANCHOR, 24);
        assertThat(r.charges()).hasSize(1);
        assertThat(r.charges().get(0).amount()).isEqualByComparingTo("0");

        AccrualCalculator.State bad = new AccrualCalculator.State(PaymentType.MONTHLY, false, true, ANCHOR, null, null,
            GroupStatus.ACTIVE, BigDecimal.valueOf(500_000), BigDecimal.valueOf(150));
        assertThatThrownBy(() -> due(bad, ANCHOR, 24)).hasMessage("student.discount.invalid");
    }
}
