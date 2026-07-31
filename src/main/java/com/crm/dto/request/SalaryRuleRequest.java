package com.crm.dto.request;

import com.crm.entity.enums.UserRole;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class SalaryRuleRequest {
    @NotNull
    private UserRole role;
    private Long userId;
    private BigDecimal baseSalary;
    private BigDecimal perStudentFee;
    private BigDecimal newStudentBonus;
    private Integer kpiThreshold;
    private BigDecimal kpiBonus;
    private Boolean isActive;
    private LocalDate effectiveFrom;
}
