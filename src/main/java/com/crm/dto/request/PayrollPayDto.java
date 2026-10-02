package com.crm.dto.request;

import com.crm.entity.enums.PaymentMethod;
import lombok.Data;

import java.math.BigDecimal;

/** {@code POST /api/payroll/{id}/pay} — APPROVED → PAID (payroll-v2 §5.2). */
@Data
public class PayrollPayDto {
    /** Oylik qanday to'landi. Ko'rsatilmasa — CASH. */
    private PaymentMethod paymentMethod;
    /** Kassa berilsa — kassaga chiqim (EXPENSE) yoziladi; berilmasa (bank o'tkazmasi) — yozilmaydi. */
    private Long cashRegisterId;
    /** Kassaga yoziladigan usulni majburan belgilaydi (PaymentMethod nomi, eski nomlar ham:
     *  "PLASTIC" → CARD, "BANK_TRANSFER" → BANK). Ko'rsatilmasa — paymentMethod. */
    private String paymentMethodForCash;
    /** Faqat CASH_AND_CARD uchun: naqd qismi (cashPart + cardPart = netSalary). */
    private BigDecimal cashPart;
    /** Faqat CASH_AND_CARD uchun: karta qismi. */
    private BigDecimal cardPart;
    /** {@code Idempotency-Key} sarlavhasi o'rniga (≤ 64 belgi). Sarlavha ustun. */
    private String idempotencyKey;
}
