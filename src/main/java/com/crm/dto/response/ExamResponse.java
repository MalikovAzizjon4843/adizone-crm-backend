package com.crm.dto.response;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ExamResponse {
    private Long id;
    private UUID uuid;
    private String examName;
    private String examType;
    private Long classId;
    private String className;
    private Long subjectId;
    private String subjectName;
    private LocalDate examDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private BigDecimal totalMarks;
    private BigDecimal passMarks;
    private String academicYear;
    private Boolean isActive;
    private LocalDateTime createdAt;
}
