package com.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TaskCreateRequest {

    @NotBlank(message = "{task.title.required}")
    private String title;

    private String description;

    /** CALL, MEETING, MESSAGE, OTHER. Berilmasa CALL. */
    private String type;

    @NotNull(message = "{task.dueAt.required}")
    private LocalDateTime dueAt;

    /** true bo'lsa dueAt shu kunning 23:59 ga keltiriladi. */
    private Boolean allDay;

    /** Berilmasa vazifa joriy foydalanuvchiga yoziladi. */
    private Long assignedTo;

    /** leadId yoki studentId — bittasi majburiy, ikkalasi birga bo'lmaydi. */
    private Long leadId;

    private Long studentId;
}
