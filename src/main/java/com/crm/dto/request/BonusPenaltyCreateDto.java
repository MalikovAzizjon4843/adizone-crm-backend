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
    @NotNull(message = "{bonusPenalty.kind.required}")
    private BonusPenaltyKind kind;
    @NotNull(message = "{bonusPenalty.targetType.required}")
    private BonusTargetType targetType;
    private Long studentId;
    private Long teacherId;
    /** STAFF (ADMIN/SALES_MANAGER/SALES_HEAD) uchun majburiy — payroll-v2 §11 #5. */
    private Long userId;
    /** Billing v2 (§6.5): STUDENT uchun ixtiyoriy — bonus faqat shu guruh yozilmasiga qo'llanadi. */
    private Long groupId;
    @NotNull(message = "{bonusPenalty.amount.required}")
    @DecimalMin(value = "0.01", message = "{bonusPenalty.amount.min}")
    private BigDecimal amount;
    private String reason;
    private LocalDate effectiveDate;
}
