package com.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SectionRequest {
    @NotBlank(message = "Section name is required")
    private String sectionName;
    private Long classId;
    private Long teacherId;
    private String room;
    private Integer maxStudents;
}
