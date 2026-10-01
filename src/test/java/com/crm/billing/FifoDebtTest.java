package com.crm.billing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** §4.1 FIFO — sof funksiya. */
class FifoDebtTest {

    private static FifoDebt.Line l(long id, long amount, String date, Long related) {
        String[] p = date.split("\\.");
        return new FifoDebt.Line(id, BigDecimal.valueOf(amount),
            LocalDate.of(Integer.parseInt(p[2]), Integer.parseInt(p[1]), Integer.parseInt(p[0])), related);
    }

    private static LocalDate d(String date) {
        String[] p = date.split("\\.");
        return LocalDate.of(Integer.parseInt(p[2]), Integer.parseInt(p[1]), Integer.parseInt(p[0]));
    }

    @Test
    void noLedger_noDebt() {
        FifoDebt.Result r = FifoDebt.compute(List.of());
        assertThat(r.balance()).isEqualByComparingTo("0");
        assertThat(r.debtSince()).isNull();
    }

    /** T2.3: 15.09 va 15.10 charge (630 000 dan), to'lov 700 000 → B −560 000, debtSince 15.10. */
    @Test
    void oldestUnpaidObligation() {
        FifoDebt.Result r = FifoDebt.compute(List.of(
            l(1, -630_000, "15.09.2026", null),
            l(2, -630_000, "15.10.2026", null),
            l(3, 700_000, "20.10.2026", null)));
        assertThat(r.balance()).isEqualByComparingTo("-560000");
        assertThat(r.debtSince()).isEqualTo(d("15.10.2026"));
        assertThat(r.debt()).isEqualByComparingTo("560000");
    }

    /** T2.2: qisman to'lov debtSince ni o'zgartirmaydi. */
    @Test
    void partialPaymentKeepsDebtSince() {
        FifoDebt.Result r = FifoDebt.compute(List.of(
            l(1, -630_000, "15.09.2026", null),
            l(2, 630_000, "16.09.2026", null),
            l(3, -630_000, "15.10.2026", null),
            l(4, 300_000, "20.10.2026", null)));
        assertThat(r.balance()).isEqualByComparingTo("-330000");
        assertThat(r.debtSince()).isEqualTo(d("15.10.2026"));
    }

    /** T2.6: bekor qilingan to'lov — qarz ASL charge sanasidan (17.09 emas). */
    @Test
    void reversalRestoresOriginalChargeDate() {
        FifoDebt.Result r = FifoDebt.compute(List.of(
            l(1, -630_000, "15.09.2026", null),
            l(2, 630_000, "16.09.2026", null),
            l(3, -630_000, "16.09.2026", 2L)));
        assertThat(r.debtSince()).isEqualTo(d("15.09.2026"));
        assertThat(r.balance()).isEqualByComparingTo("-630000");
    }

    /** §6.7: PERIOD_REFUND aynan o'sha davrni kamaytiradi. */
    @Test
    void refundReducesItsOwnPeriod() {
        FifoDebt.Result r = FifoDebt.compute(List.of(
            l(1, -630_000, "15.09.2026", null),
            l(2, 357_000, "28.09.2026", 1L)));
        assertThat(r.balance()).isEqualByComparingTo("-273000");
        assertThat(r.debtSince()).isEqualTo(d("15.09.2026"));
    }

    /** §6.4: 1 890 000 to'lov bekor qilindi, 15.09 va 15.10 hisoblangan → debtSince 15.09. */
    @Test
    void cancelAfterAccrual() {
        FifoDebt.Result r = FifoDebt.compute(List.of(
            l(1, -630_000, "15.09.2026", null),
            l(2, 1_890_000, "16.09.2026", null),
            l(3, -630_000, "15.10.2026", null),
            l(4, -1_890_000, "16.09.2026", 2L)));
        assertThat(r.balance()).isEqualByComparingTo("-1260000");
        assertThat(r.debtSince()).isEqualTo(d("15.09.2026"));
    }

    /** TRANSFER_IN (asl boshqa SG da) — o'z guruhi, effective = eski debtSince. */
    @Test
    void transferInCarriesDebtDate() {
        FifoDebt.Result r = FifoDebt.compute(List.of(l(10, -540_000, "15.10.2026", 9L)));
        assertThat(r.debtSince()).isEqualTo(d("15.10.2026"));
    }

    @Test
    void creditCoversEverything() {
        FifoDebt.Result r = FifoDebt.compute(List.of(
            l(1, -630_000, "15.09.2026", null),
            l(2, 1_890_000, "16.09.2026", null)));
        assertThat(r.debtSince()).isNull();
        assertThat(r.balance()).isEqualByComparingTo("1260000");
    }
}
