package com.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
    /** Yangi uy vazifasida majburiy (400 {@code homework.group.required}). */
    private Long groupId;
    private Long teacherId;
    private LocalDate assignedDate;
    @NotNull(message = "{homework.dueDate.required}")
    private LocalDate dueDate;
    /** Maksimal ball (ixtiyoriy); berilsa o'quvchi bahosi 0..marks. */
    private BigDecimal marks;
    /** {@code POST /api/files/upload} qaytargan {@code url} ({@code /api/files/...}); bo'sh — fayl olib tashlanadi. */
    @Size(max = 500)
    private String attachmentUrl;
    @Size(max = 255)
    private String attachmentName;
}
