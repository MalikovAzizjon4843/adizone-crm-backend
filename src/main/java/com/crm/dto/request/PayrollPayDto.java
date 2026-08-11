package com.crm.dto.request;

import lombok.Data;

@Data
public class PayrollPayDto {
    private String paymentMethod;
    private Long cashRegisterId;
    /** Kassaga yoziladigan usul (PaymentMethod nomi). Ko'rsatilmasa — CASH.
     *  Eski "PLASTIC" -> CARD, "BANK_TRANSFER" -> BANK. */
    private String paymentMethodForCash;
}
