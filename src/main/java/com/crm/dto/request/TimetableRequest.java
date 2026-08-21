package com.crm.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalTime;
import java.util.List;

@Data
public class TimetableRequest {
    /** CRM guruhi (ixtiyoriy) */
    private Long groupId;
    private Long classId;
    private Long sectionId;
    private Long subjectId;
    private Long teacherId;
    private Long classroomId;

    /** Eski format: bitta kun */
    private String dayOfWeek;

    /** Yangi format: bir necha kun */
    private List<String> daysOfWeek;

    @NotNull(message = "{timetable.startTime.required}")
    private LocalTime startTime;

    @NotNull(message = "{timetable.endTime.required}")
    private LocalTime endTime;

    private String academicYear;

    /** Xona raqami bo'yicha classroom tanlanadi (ixtiyoriy) */
    private String roomNumber;
}
