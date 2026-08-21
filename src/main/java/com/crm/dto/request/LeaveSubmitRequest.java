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

    @NotBlank(message = "{leaveSubmit.leaveType.required}")
    private String leaveType;

    @NotNull(message = "{leaveSubmit.fromDate.required}")
    private LocalDate fromDate;

    @NotNull(message = "{leaveSubmit.toDate.required}")
    private LocalDate toDate;

    private String reason;
}
