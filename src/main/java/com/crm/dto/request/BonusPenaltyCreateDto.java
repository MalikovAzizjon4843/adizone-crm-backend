package com.crm.dto.request;

import com.crm.entity.enums.BonusPenaltyKind;
import com.crm.entity.enums.BonusTargetType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class BonusPenaltyCreateDto {
    @NotNull
    private BonusPenaltyKind kind;
    @NotNull
    private BonusTargetType targetType;
    private Long studentId;
    private Long teacherId;
    @NotNull
    @DecimalMin("0.01")
    private BigDecimal amount;
    private String reason;
    private LocalDate effectiveDate;
}
