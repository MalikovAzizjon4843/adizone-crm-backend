package com.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class BalanceAdjustRequest {
    @NotNull
    private Long groupId;
    @NotNull
    private BigDecimal amount;
    @NotBlank(message = "Sabab majburiy")
    private String note;
}
