package com.crm.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.time.LocalDate;

@Data
public class AttendanceUnlockCreateDto {
    @NotNull(message = "Group ID is required")
    private Long groupId;

    @NotNull(message = "Attendance date is required")
    private LocalDate attendanceDate;

    private String note;
}
