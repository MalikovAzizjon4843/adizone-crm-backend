package com.crm.dto.response;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class HomeworkSubmissionResponse {
    private Long id;
    private Long homeworkId;
    private String homeworkTitle;
    private Long studentId;
    private String studentName;
    private LocalDateTime submittedAt;
    private String fileUrl;
    private String remarks;
    private BigDecimal marksObtained;
    private String status;
    private LocalDateTime createdAt;
}
