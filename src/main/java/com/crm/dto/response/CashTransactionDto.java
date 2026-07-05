package com.crm.dto.response;

import com.crm.entity.enums.CashPaymentMethod;
import com.crm.entity.enums.CashTransactionStatus;
import com.crm.entity.enums.CashTransactionType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class CashTransactionDto {
    private Long id;
    private String uuid;
    private Long cashRegisterId;
    private CashTransactionType type;
    private CashPaymentMethod paymentMethod;
    private Long studentId;
    private String studentName;
    private Long teacherId;
    private String teacherName;
    private String transactionName;
    private BigDecimal amount;
    private String note;
    private CashTransactionStatus status;
    private LocalDate periodMonth;
    private BigDecimal totalAmount;
    private LocalDate transactionDate;
    private LocalDateTime createdAt;
    private String createdByName;
}
