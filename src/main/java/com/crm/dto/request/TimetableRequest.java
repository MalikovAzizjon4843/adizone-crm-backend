package com.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalTime;

@Data
public class TimetableRequest {
    /** CRM guruhi (ixtiyoriy) */
    private Long groupId;
    private Long classId;
    private Long sectionId;
    private Long subjectId;
    private Long teacherId;
    private Long classroomId;

    @NotBlank(message = "Day of week is required")
    private String dayOfWeek;

    @NotNull(message = "Start time is required")
    private LocalTime startTime;

    @NotNull(message = "End time is required")
    private LocalTime endTime;

    private String academicYear;

    /** Xona raqami bo'yicha classroom tanlanadi (ixtiyoriy) */
    private String roomNumber;
}
