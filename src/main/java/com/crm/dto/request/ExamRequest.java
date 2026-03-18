package com.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

@Data
public class ExamRequest {
    @NotBlank(message = "Exam name is required")
    private String examName;
    private String examType;
    private Long classId;
    private Long subjectId;
    private LocalDate examDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private BigDecimal totalMarks;
    private BigDecimal passMarks;
    private String academicYear;
}
