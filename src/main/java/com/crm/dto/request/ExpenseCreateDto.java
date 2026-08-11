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
    private LocalDate transactionDate;
    private String note;
}
