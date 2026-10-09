package com.crm.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Vazifani bekor qilish ({@code PATCH /api/tasks/{id}/cancel}) yoki o'chirish
 * ({@code DELETE /api/tasks/{id}}, tana ixtiyoriy) — ikkalasi ham ixtiyoriy.
 *
 * <p>{@code nextTask} lid bosqichi {@code requires_task} bo'lib, bu vazifa lidning
 * oxirgi ochiq vazifasi bo'lsa majburiy (400 {@code lead.task.required}).
 */
@Data
public class TaskCloseRequest {

    /** Bekor qilish sababi — {@code tasks.result} ga yoziladi. O'chirishda e'tiborsiz. */
    @Size(max = 1000, message = "{task.reason.size}")
    private String reason;

    @Valid
    private NextTaskRequest nextTask;
}
