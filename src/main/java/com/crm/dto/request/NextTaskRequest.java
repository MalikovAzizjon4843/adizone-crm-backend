package com.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Lidning keyingi vazifasi — amal bilan BIR tranzaksiyada yaratiladi.
 *
 * <p>Bosqichi {@code requires_task} bo'lgan lidda boshqa ochiq vazifa qolmasa
 * majburiy (aks holda 400 {@code lead.task.required}):
 * <ul>
 *   <li>bosqichni o'zgartirish — {@code PATCH /api/leads/{id}/status};</li>
 *   <li>vazifani bekor qilish — {@code PATCH /api/tasks/{id}/cancel};</li>
 *   <li>vazifani o'chirish — {@code DELETE /api/tasks/{id}} tanasi.</li>
 * </ul>
 * Vazifani bajarishda ({@code /complete}) avvalgi {@code TaskCompleteRequest.NextTask}
 * shakli qoladi — frontend buzilmasin.
 *
 * <p>{@code @Valid} kaskadi faqat obyekt berilganda ishlaydi — qoida talab
 * qilmaganda maydon butunlay tushirib qoldirilishi mumkin.
 */
@Data
public class NextTaskRequest {

    @NotBlank(message = "{task.title.required}")
    @Size(max = 255, message = "{task.title.size}")
    private String title;

    /** Kelajakda bo'lishi shart ({@code task.dueAt.future}). */
    @NotNull(message = "{task.dueAt.required}")
    private LocalDateTime dueAt;

    /** Mas'ul. SALES_MANAGER faqat o'zini qo'ya oladi ({@code TaskService.resolveAssignee}). */
    @NotNull(message = "{task.assignedTo.required}")
    private Long assigneeId;

    /** Ixtiyoriy — vazifa tavsifiga ({@code description}) tushadi. */
    private String comment;

    /** CALL, MEETING, MESSAGE, OTHER. Berilmasa CALL. */
    private String type;

    /** true bo'lsa dueAt shu kunning 23:59 ga keltiriladi. */
    private Boolean allDay;
}
