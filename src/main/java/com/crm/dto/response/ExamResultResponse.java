package com.crm.dto.response;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ExamResultResponse {
    private Long id;
    private Long examId;
    private String examName;
    private Long studentId;
    private String studentName;
    private BigDecimal marksObtained;
    private BigDecimal totalMarks;
    private String grade;
    private String remarks;
    private Boolean isPassed;
    private LocalDateTime createdAt;
}
