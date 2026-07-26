package com.crm.config;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Kutilayotgan to'lovlar oralig'i chegarasi.
 * Hozir: ertadan → joriy oy oxiri. Kelajakda sozlamadan o'qiladi.
 */
public final class PaymentScheduleConfig {

    /** DB/API settings kaliti (hali ishlatilmaydi). */
    public static final String SETTING_EXPECTED_PAYMENTS_UNTIL = "expected_payments_until";

    private PaymentScheduleConfig() {}

    /** Default `from`: ertadan (bugun+1). */
    public static LocalDate defaultExpectedPaymentsFrom(LocalDate today) {
        return today.plusDays(1);
    }

    /** Default `to`: joriy oyning oxirgi kuni (sozlamada yo'q — shu ishlatiladi). */
    public static LocalDate defaultExpectedPaymentsTo(LocalDate today) {
        return YearMonth.from(today).atEndOfMonth();
    }
}
