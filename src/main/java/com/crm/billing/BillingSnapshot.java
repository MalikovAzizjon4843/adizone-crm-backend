package com.crm.billing;

import com.crm.entity.enums.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Bitta yozilmaning hisob holati — ledgerdan hosila (docs/design/billing-v2.md §4).
 *
 * @param balance           B = Σ ledger
 * @param debt              max(0, −B)
 * @param debtSince         FIFO bo'yicha eng eski to'lanmagan majburiyat sanasi
 * @param status            ko'rsatish holati (§4.2 jadvali: OVERDUE/PENDING/FROZEN/TRIAL/PAID)
 * @param nextPaymentDate   §4.3
 * @param nextPaymentAmount §4.3
 * @param effectiveFee      c(sg) yoki l(sg) — chegirmadan keyingi joriy narx
 */
public record BillingSnapshot(
    BigDecimal balance,
    BigDecimal debt,
    LocalDate debtSince,
    PaymentStatus status,
    LocalDate nextPaymentDate,
    BigDecimal nextPaymentAmount,
    BigDecimal effectiveFee) {
}
