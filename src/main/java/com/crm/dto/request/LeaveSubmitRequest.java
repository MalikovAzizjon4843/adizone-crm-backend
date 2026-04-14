package com.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class LeaveSubmitRequest {
    /** Ixtiyoriy — bo‘lmasa {@link #teacherId} orqali aniqlanadi */
    private Long requesterId;

    /** Frontend o‘qituvchi tanlaydi — user bilan bog‘langan {@link com.crm.entity.User} qidiriladi */
    private Long teacherId;

    @NotBlank(message = "Leave type is required")
    private String leaveType;

    @NotNull(message = "From date is required")
    private LocalDate fromDate;

    @NotNull(message = "To date is required")
    private LocalDate toDate;

    private String reason;
}
