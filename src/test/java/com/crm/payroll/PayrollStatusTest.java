package com.crm.payroll;

import com.crm.entity.enums.PayrollStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** v1 → v2 holat o'qilishi (payroll-v2 §1): V54 gacha ham yozuv o'qilishi yiqilmasin. */
class PayrollStatusTest {

    @Test
    void fromDb_mapsLegacyPendingAndUnknownToDraft() {
        assertThat(PayrollStatus.fromDb("PENDING")).isEqualTo(PayrollStatus.DRAFT);
        assertThat(PayrollStatus.fromDb(" paid ")).isEqualTo(PayrollStatus.PAID);
        assertThat(PayrollStatus.fromDb("to'lanmagan")).isEqualTo(PayrollStatus.DRAFT);
        assertThat(PayrollStatus.fromDb(null)).isEqualTo(PayrollStatus.DRAFT);
    }

    @Test
    void parseOrNull_isStrictForFilters() {
        assertThat(PayrollStatus.parseOrNull("approved")).isEqualTo(PayrollStatus.APPROVED);
        assertThat(PayrollStatus.parseOrNull("PENDING")).isEqualTo(PayrollStatus.DRAFT);
        assertThat(PayrollStatus.parseOrNull("XYZ")).isNull();
        assertThat(PayrollStatus.parseOrNull(" ")).isNull();
    }
}
