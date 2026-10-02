package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Oylik hisobi (preview) — {@code GET /api/payroll/calculate}. Payroll v2 da v1 dagi
 * {@code details} (Map) va {@code students} o'rniga {@link #calculationDetails} — tuzilgan obyekt
 * (docs/design/payroll-v2.md §6).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalaryCalculationDto {
    private Long userId;
    private String fullName;
    private String role;
    private Integer month;
    private Integer year;

    /** = qoidaning {@code fixedSalary}. */
    private BigDecimal baseSalary;
    private Integer paidStudentCount;
    /** TEACHER: to'lagan birliklar (davrlar + PER_LESSON ulushlari, kasr bo'lishi mumkin — §11 #2). */
    private BigDecimal paidStudentUnits;
    private BigDecimal perStudentAmount;
    private Integer newStudentCount;
    private BigDecimal newStudentAmount;
    private Boolean kpiApplied;
    private BigDecimal kpiAmount;
    /** ADMIN: oy oxirida hisob davri bor o'quvchilar (§2.3). */
    private Integer totalActiveStudents;
    private BigDecimal grossAmount;
    private BigDecimal bonusPenaltyAdjustment;
    /** net = gross + bonusPenaltyAdjustment. */
    private BigDecimal totalAmount;

    private Boolean calculable;
    private String message;
    /** calculable=false sababi: RULE_NOT_FOUND, TEACHER_PROFILE_MISSING, TEACHER_PROFILE_LINKED_TO_OTHER_USER, ROLE_NOT_CALCULATED. */
    private String messageCode;

    private PayrollCalculationDetails calculationDetails;
}
