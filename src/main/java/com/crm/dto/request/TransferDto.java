package com.crm.dto.request;

import com.crm.entity.enums.PaymentMethod;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class TransferDto {
    private Long fromCashRegisterId;
    private Long toCashRegisterId;
    private BigDecimal amount;
    private PaymentMethod paymentMethod;
    /** Faqat CASH_AND_CARD uchun: naqd qismi (cashPart + cardPart = amount). */
    private BigDecimal cashPart;
    /** Faqat CASH_AND_CARD uchun: karta qismi. */
    private BigDecimal cardPart;
    private String note;
}
