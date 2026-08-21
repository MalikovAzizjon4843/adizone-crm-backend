package com.crm.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.time.LocalDate;

@Data
public class AttendanceUnlockCreateDto {
    @NotNull(message = "{attendanceUnlock.groupId.required}")
    private Long groupId;

    @NotNull(message = "{attendanceUnlock.attendanceDate.required}")
    private LocalDate attendanceDate;

    private String note;
}
