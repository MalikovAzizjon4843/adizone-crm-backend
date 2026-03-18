package com.crm.dto.response;

import lombok.*;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SectionResponse {
    private Long id;
    private String sectionName;
    private Long classId;
    private String className;
    private Long teacherId;
    private String teacherName;
    private String room;
    private Integer maxStudents;
    private Boolean isActive;
    private LocalDateTime createdAt;
}
