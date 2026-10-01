package com.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/** {@code POST /api/students/{id}/balance-transfer} (SA, billing v2 §13 #6). */
@Data
public class BalanceTransferRequest {
    @NotNull(message = "{balanceTransfer.fromGroupId.required}")
    private Long fromGroupId;
    @NotNull(message = "{balanceTransfer.toGroupId.required}")
    private Long toGroupId;
    @NotNull(message = "{payment.amount.positive}")
    private BigDecimal amount;
    @NotBlank(message = "{balanceTransfer.note.required}")
    private String note;
}
