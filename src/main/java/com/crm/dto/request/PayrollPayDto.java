package com.crm.dto.request;

import com.crm.entity.enums.PaymentMethod;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class PayrollPayDto {
    /** Oylik qanday to'landi. Ko'rsatilmasa — CASH. */
    private PaymentMethod paymentMethod;
    private Long cashRegisterId;
    /** Kassaga yoziladigan usulni majburan belgilaydi (PaymentMethod nomi).
     *  Ko'rsatilmasa — paymentMethod ishlatiladi. Eski "PLASTIC" -> CARD,
     *  "BANK_TRANSFER" -> BANK. */
    private String paymentMethodForCash;
    /** Faqat CASH_AND_CARD uchun: naqd qismi (cashPart + cardPart = netSalary). */
    private BigDecimal cashPart;
    /** Faqat CASH_AND_CARD uchun: karta qismi. */
    private BigDecimal cardPart;
}
