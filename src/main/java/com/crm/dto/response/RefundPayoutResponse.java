package com.crm.dto.response;

import com.crm.entity.enums.PaymentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** {@code POST /api/students/{id}/refund-payout} javobi (billing v2, §13 #24). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundPayoutResponse {
    private Long studentId;
    private String studentName;
    private Long groupId;
    private Long studentGroupId;
    private BigDecimal amount;
    private String reason;
    private Long cashTransactionId;
    private BillingLineDto line;
    private BigDecimal balanceAfter;
    private BigDecimal debtAfter;
    private PaymentStatus statusAfter;
}
