package com.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SubjectRequest {
    @NotBlank(message = "Subject name is required")
    private String subjectName;
    private String subjectCode;
    private Long classId;
    private Long teacherId;
}
