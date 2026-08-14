package com.crm.dto.request;

import com.crm.entity.enums.PaymentMethod;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class IncomeCreateDto {
    private String transactionType;
    private Long studentId;
    private BigDecimal amount;
    private PaymentMethod paymentMethod;
    /** Faqat CASH_AND_CARD uchun: naqd qismi (cashPart + cardPart = amount). */
    private BigDecimal cashPart;
    /** Faqat CASH_AND_CARD uchun: karta qismi. */
    private BigDecimal cardPart;
    private LocalDate transactionDate;
    private String note;
}
