package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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

    private BigDecimal baseSalary;
    private Integer paidStudentCount;
    private BigDecimal perStudentAmount;
    private Integer newStudentCount;
    private BigDecimal newStudentAmount;
    private Boolean kpiApplied;
    private BigDecimal kpiAmount;
    private Integer totalActiveStudents;
    private BigDecimal bonusPenaltyAdjustment;
    private BigDecimal totalAmount;

    private Boolean calculable;
    private String message;

    private Map<String, Object> details;
    private List<StudentDetailItem> students;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StudentDetailItem {
        private Long studentId;
        private String name;
        private Long groupId;
        private String groupName;
        private LocalDate paymentDate;
        private String type; // PAID | NEW
    }
}
