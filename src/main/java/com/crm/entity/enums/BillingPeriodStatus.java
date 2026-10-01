package com.crm.entity.enums;

/** {@code billing_periods.status} — docs/design/billing-v2.md §3.3. */
public enum BillingPeriodStatus {
    /** PERIOD_CHARGE yozilgan (yoki d = 100 bo'lib summa 0). */
    CHARGED,
    /** Muzlatishda ishlatilmagan qismi qaytarilgan. */
    PARTIALLY_REFUNDED,
    /** To'liq qaytarilgan (muzlatish sanasidan keyin boshlangan davr). */
    REFUNDED,
    /** Migratsiya: R dan oldingi davr, ledger yozuvi yo'q (§9.2). */
    MIGRATED,
    /** Migratsiya: eski modelda oldindan to'langan davr belgisi. */
    PREPAID_LEGACY
}
