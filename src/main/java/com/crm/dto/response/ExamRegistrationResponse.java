package com.crm.dto.response;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamRegistrationResponse {
    private Long id;
    private Long examId;
    private Long studentId;
    private String studentName;
    private String paymentStatus;
    private BigDecimal amountDue;
    private BigDecimal amountPaid;
    private LocalDate registrationDate;
    private String status;
    private String notes;
}
