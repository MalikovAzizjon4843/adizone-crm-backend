package com.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class HomeworkRequest {
    @NotBlank(message = "{homework.title.required}")
    private String title;
    private String description;
    private Long subjectId;
    private Long classId;
    private Long groupId;
    private Long teacherId;
    private LocalDate assignedDate;
    @NotNull(message = "{homework.dueDate.required}")
    private LocalDate dueDate;
    private BigDecimal marks;
}
