package com.crm.dto.response;

import com.crm.entity.enums.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalaryRuleResponse {
    private Long id;
    private UserRole role;
    private Long userId;
    private String userName;
    private BigDecimal baseSalary;
    private BigDecimal perStudentFee;
    private BigDecimal newStudentBonus;
    private Integer kpiThreshold;
    private BigDecimal kpiBonus;
    private Boolean isActive;
    private LocalDate effectiveFrom;
    private LocalDateTime createdAt;
}
