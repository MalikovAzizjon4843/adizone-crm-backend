package com.crm.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** {@code PUT /api/homework/{id}/students} — o'qituvchi o'quvchilar holatini belgilaydi (phase6-api §4). */
@Data
public class HomeworkGradeRequest {

    @NotEmpty(message = "{homework.items.required}")
    @Size(max = 200, message = "{homework.items.required}")
    @Valid
    private List<Item> items;

    @Data
    public static class Item {
        @NotNull(message = "{homeworkSubmission.studentId.required}")
        private Long studentId;
        /** SUBMITTED | LATE | NOT_SUBMITTED. */
        @NotNull(message = "{homework.status.required}")
        private String status;
        private BigDecimal marksObtained;
        @Size(max = 1000)
        private String remarks;
        /** Ixtiyoriy; SUBMITTED/LATE da berilmasa — hozir. */
        private LocalDateTime submittedAt;
    }
}
