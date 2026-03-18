package com.crm.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class HomeworkSubmissionRequest {
    @NotNull(message = "Student ID is required")
    private Long studentId;
    private LocalDateTime submittedAt;
    private String fileUrl;
    private String remarks;
    private BigDecimal marksObtained;
    private String status;
}
