package com.crm.dto.request;

import com.crm.entity.enums.PaymentMethod;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class ExpenseCreateDto {
    private Long studentId;
    private BigDecimal amount;
    private LocalDate periodMonth;
    private BigDecimal totalAmount;
    private PaymentMethod paymentMethod;
    /** Faqat CASH_AND_CARD uchun: naqd qismi (cashPart + cardPart = amount). */
    private BigDecimal cashPart;
    /** Faqat CASH_AND_CARD uchun: karta qismi. */
    private BigDecimal cardPart;
    private LocalDate transactionDate;
    private String note;
    /** Kategoriya kodi (V86): ko'rsatilmasa — OTHER_DAILY; admin — faqat DAILY, STRATEGIC — SA, Direktor kassasi. */
    private String category;
}
