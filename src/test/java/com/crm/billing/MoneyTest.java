package com.crm.billing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** docs/design/billing-v2.md §1.3, §6.3, §6.7 — sof funksiyalar, Spring'siz. */
class MoneyTest {

    private static BigDecimal b(String v) {
        return new BigDecimal(v);
    }

    @Test
    void uzs_roundsHalfUpToWholeSum() {
        assertThat(Money.uzs(b("309999.69"))).isEqualByComparingTo("310000");
        assertThat(Money.uzs(b("486110.5"))).isEqualByComparingTo("486111");
        assertThat(Money.uzs(b("486110.49"))).isEqualByComparingTo("486110");
        assertThat(Money.uzs(b("284516.13"))).isEqualByComparingTo("284516");
        assertThat(Money.uzs(b("-0.5"))).isEqualByComparingTo("-1");
        assertThat(Money.uzs(null)).isEqualByComparingTo("0");
        assertThat(Money.uzs(b("630000.00")).scale()).isZero();
    }

    /** §6.3 jadvali (T3.1). */
    @Test
    void discounted_matchesDesignTable() {
        assertThat(Money.discounted(b("700000"), b("10"))).isEqualByComparingTo("630000");
        assertThat(Money.discounted(b("650000"), b("15"))).isEqualByComparingTo("552500");
        assertThat(Money.discounted(b("333333"), b("7"))).isEqualByComparingTo("310000");
        assertThat(Money.discounted(b("555555"), b("12.5"))).isEqualByComparingTo("486111");
        assertThat(Money.discounted(b("500000"), b("100"))).isEqualByComparingTo("0");
        assertThat(Money.discounted(b("80000"), b("10"))).isEqualByComparingTo("72000");
        assertThat(Money.discounted(b("75000"), b("15"))).isEqualByComparingTo("63750");
        assertThat(Money.discounted(b("700000"), null)).isEqualByComparingTo("700000");
    }

    /** §6.7 muzlatish qaytarimi — charge summasidan, bir marta yaxlitlanadi. */
    @Test
    void proportion_freezeRefundExamples() {
        assertThat(Money.proportion(b("630000"), 17, 30)).isEqualByComparingTo("357000");
        assertThat(Money.proportion(b("630000"), 14, 31)).isEqualByComparingTo("284516");
        assertThat(Money.proportion(b("630000"), 31, 31)).isEqualByComparingTo("630000");
    }

    @Test
    void isWhole_rejectsFractions() {
        assertThat(Money.isWhole(b("630000"))).isTrue();
        assertThat(Money.isWhole(b("630000.00"))).isTrue();
        assertThat(Money.isWhole(b("0"))).isTrue();
        assertThat(Money.isWhole(b("630000.50"))).isFalse();
        assertThat(Money.isWhole(b("0.01"))).isFalse();
    }

    @Test
    void floorDiv_forPrepaidPeriods() {
        assertThat(Money.floorDiv(b("1260000"), b("630000"))).isEqualTo(2);
        assertThat(Money.floorDiv(b("300000"), b("630000"))).isZero();
        assertThat(Money.floorDiv(b("1259999"), b("630000"))).isEqualTo(1);
    }
}
