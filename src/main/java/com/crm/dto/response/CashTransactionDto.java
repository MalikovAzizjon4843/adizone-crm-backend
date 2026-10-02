package com.crm.dto.response;

import com.crm.entity.enums.PaymentMethod;
import com.crm.entity.enums.CashDirection;
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
    private PaymentMethod paymentMethod;
    private Long studentId;
    private String studentName;
    private Long teacherId;
    private String teacherName;
    private String transactionName;
    private BigDecimal amount;
    /** IN — kassaga kirdi, OUT — chiqdi ({@link com.crm.entity.enums.CashDirection}). */
    private CashDirection direction;
    /** IN → +amount, OUT → −amount. */
    private BigDecimal signedAmount;
    /** O'"'"'quvchi to'"'"'lovi (kirim va uning REVERSAL i). */
    private Long paymentId;
    /** Oylik to'"'"'lovi (chiqim va uning REVERSAL i). */
    private Long payrollId;
    /** REVERSAL → asl yozuv; TRANSFER kirim qatori → chiqim qatori. */
    private Long relatedTxId;
    /** Faqat CASH_AND_CARD uchun to'ldiriladi. */
    private BigDecimal cashPart;
    private BigDecimal cardPart;
    private String note;
    private CashTransactionStatus status;
    private LocalDate periodMonth;
    private BigDecimal totalAmount;
    private LocalDate transactionDate;
    private LocalDateTime createdAt;
    private String createdByName;
}
