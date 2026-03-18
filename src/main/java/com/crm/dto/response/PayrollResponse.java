package com.crm.dto.response;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PayrollResponse {
    private Long id;
    private UUID uuid;
    private Long teacherId;
    private String teacherName;
    private Integer month;
    private Integer year;
    private BigDecimal basicSalary;
    private BigDecimal allowances;
    private BigDecimal deductions;
    private BigDecimal netSalary;
    private LocalDate paymentDate;
    private String paymentMethod;
    private String status;
    private String notes;
    private String createdByName;
    private LocalDateTime createdAt;
}
