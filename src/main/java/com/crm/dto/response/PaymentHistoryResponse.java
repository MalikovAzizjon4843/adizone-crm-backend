package com.crm.dto.response;

import com.crm.entity.enums.PaymentMethod;
import com.crm.entity.enums.PaymentStatus;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentHistoryResponse {
    private String receiptNumber;
    private String studentName;
    private String groupName;
    private BigDecimal amount;
    private LocalDate paymentDate;
    private PaymentMethod paymentMethod;
    private LocalDate periodFrom;
    private LocalDate periodTo;
    private PaymentStatus status;
    private String description;
}
