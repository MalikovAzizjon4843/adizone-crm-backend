package com.crm.dto.request;

import lombok.Data;

@Data
public class CashRegisterCreateDto {
    private String name;
    private Long moderatorId;
    private Boolean acceptOnlinePayment;
    private Boolean archived;
    /** ADMIN | DIRECTOR (V86); yaratishda ko'rsatilmasa — ADMIN. */
    private String kind;
    /** Razmen: topshirishdan keyin kassada qoladigan naqd (SA belgilaydi). */
    private java.math.BigDecimal floatAmount;
}
