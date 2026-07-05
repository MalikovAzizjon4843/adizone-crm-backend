package com.crm.dto.request;

import com.crm.entity.enums.CashPaymentMethod;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class IncomeCreateDto {
    private String transactionType;
    private Long studentId;
    private BigDecimal amount;
    private CashPaymentMethod paymentMethod;
    private LocalDate transactionDate;
    private String note;
}
