package com.crm.dto.response;

import com.crm.entity.enums.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Oylik qoidasi — nomlar {@link com.crm.dto.request.SalaryRuleRequest} bilan bir xil (payroll-v2 §7). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalaryRuleResponse {
    private Long id;
    private UserRole role;
    private Long userId;
    private String userName;
    private BigDecimal fixedSalary;
    private BigDecimal perPayingStudent;
    private BigDecimal perNewStudent;
    private Integer kpiThreshold;
    private BigDecimal kpiBonus;
    private BigDecimal substituteLessonRate;
    private Boolean isActive;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
