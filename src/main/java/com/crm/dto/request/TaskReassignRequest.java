package com.crm.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TaskReassignRequest {

    @NotNull(message = "{task.assignedTo.required}")
    private Long userId;
}
