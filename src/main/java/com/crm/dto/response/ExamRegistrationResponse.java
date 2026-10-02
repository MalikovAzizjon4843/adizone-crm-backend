package com.crm.dto.response;

import com.crm.entity.enums.ExamPaymentStatus;
import com.crm.entity.enums.ExamRegistrationStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamRegistrationResponse {
    private Long id;
    private Long examId;
    private String examName;
    private Long studentId;
    private String studentName;
    private ExamRegistrationStatus status;
    private ExamPaymentStatus paymentStatus;
    private BigDecimal amountDue;
    private BigDecimal amountPaid;
    private Long cashTransactionId;
    private Long refundCashTransactionId;
    private String receiptNumber;
    private LocalDate registrationDate;
    private LocalDateTime cancelledAt;
    private String cancelReason;
    private String notes;
    /** Bekor qilishda: kassa chelagi manfiyga tushdi (ogohlantirish, rad etilmaydi). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean negativeCashBalance;
    /** {@code Idempotency-Key} takrori — yangi yozuv yaratilmadi. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean idempotentReplay;
}
