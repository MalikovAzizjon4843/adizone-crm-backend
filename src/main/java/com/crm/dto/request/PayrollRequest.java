package com.crm.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class PayrollRequest {
    @NotNull(message = "{payroll.teacherId.required}")
    private Long teacherId;

    @NotNull(message = "{payroll.month.required}")
    @Min(value = 1, message = "{payroll.month.min}") @Max(value = 12, message = "{payroll.month.max}")
    private Integer month;

    @NotNull(message = "{payroll.year.required}")
    private Integer year;

    private BigDecimal basicSalary;
    private BigDecimal allowances;
    private BigDecimal deductions;
    private BigDecimal netSalary;
    private LocalDate paymentDate;
    private String paymentMethod;
    private String status;
    private String notes;
    private Long createdById;
}
