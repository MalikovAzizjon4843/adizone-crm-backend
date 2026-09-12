package com.crm.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
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

    /**
     * Vazifa bog'lanadigan obyekt. Ikkalasi ham null bo'lishi mumkin —
     * u holda vazifa mustaqil bo'ladi (hech qanday lid yoki o'quvchiga
     * tegishli emas). Ikkalasini BIRGA berish mumkin emas.
     */
    private Long leadId;

    private Long studentId;

    /**
     * Bir vazifa ikkita obyektga bog'lanmasligi. {@code Task} entity'sida
     * ikkalasi ham nullable ustun, shuning uchun cheklov shu yerda va
     * {@code TaskService.create()} da tekshiriladi.
     */
    @JsonIgnore
    @AssertTrue(message = "{task.target.single}")
    public boolean isTargetValid() {
        return leadId == null || studentId == null;
    }
}
