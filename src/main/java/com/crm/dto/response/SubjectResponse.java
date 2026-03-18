package com.crm.dto.response;

import lombok.*;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SubjectResponse {
    private Long id;
    private String subjectName;
    private String subjectCode;
    private Long classId;
    private String className;
    private Long teacherId;
    private String teacherName;
    private Boolean isActive;
    private LocalDateTime createdAt;
}
