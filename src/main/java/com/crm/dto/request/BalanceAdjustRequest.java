package com.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class BalanceAdjustRequest {
    /** Billing v2: ixtiyoriy, default bugun ({@code ≤ bugun}); FIFO shu sana bilan. */
    private java.time.LocalDate effectiveDate;

    @NotNull(message = "{balanceAdjust.groupId.required}")
    private Long groupId;
    @NotNull(message = "{balanceAdjust.amount.required}")
    private BigDecimal amount;
    @NotBlank(message = "{balanceAdjust.note.required}")
    private String note;
}
