package com.crm.dto.response;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class HomeworkResponse {
    private Long id;
    private UUID uuid;
    private String title;
    private String description;
    private Long subjectId;
    private String subjectName;
    private Long classId;
    private String className;
    private Long groupId;
    private String groupName;
    private Long teacherId;
    private String teacherName;
    private LocalDate assignedDate;
    private LocalDate dueDate;
    private BigDecimal marks;
    private Boolean isActive;
    private LocalDateTime createdAt;
}
