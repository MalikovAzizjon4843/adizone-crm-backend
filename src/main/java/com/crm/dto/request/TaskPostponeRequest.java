package com.crm.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TaskPostponeRequest {

    @NotNull(message = "{task.dueAt.required}")
    private LocalDateTime dueAt;

    private Boolean allDay;
}
