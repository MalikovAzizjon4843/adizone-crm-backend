package com.crm.dto.request;

import com.crm.entity.enums.PaymentMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * {@code POST /api/students/{id}/refund-payout} — o'quvchiga musbat balansdan naqd
 * qaytarish (billing v2, §13 #24 qarori).
 */
@Data
public class RefundPayoutRequest {
    /** O'quvchida musbat balansli SG > 1 bo'lsa majburiy. */
    private Long groupId;

    @NotNull(message = "{refund.amount.required}")
    private BigDecimal amount;

    @NotNull(message = "{refund.cashRegister.required}")
    private Long cashRegisterId;

    private PaymentMethod paymentMethod = PaymentMethod.CASH;

    /** Faqat CASH_AND_CARD uchun. */
    private BigDecimal cashPart;
    private BigDecimal cardPart;

    @NotBlank(message = "{refund.reason.required}")
    @Size(min = 3, max = 500, message = "{refund.reason.required}")
    private String reason;
}
