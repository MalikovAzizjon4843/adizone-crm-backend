package com.crm.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ExamResultRequest {
    @NotNull(message = "Student ID is required")
    private Long studentId;
    private BigDecimal marksObtained;
    private String grade;
    private String remarks;
    private Boolean isPassed;
}
