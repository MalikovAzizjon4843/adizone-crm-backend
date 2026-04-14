package com.crm.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class GroupRequest {
    @NotBlank
    private String groupName;
    @NotNull
    private Long courseId;
    private Long teacherId;
    private String room;
    @Min(1)
    private Integer maxStudents = 20;
    @NotNull
    private LocalDate startDate;
    private LocalDate endDate;
    private String notes;
    /** Legacy: frontend JSON "schedules" — {@link #scheduleDays} bo‘sh bo‘lsa shu yerga map qilinadi */
    private List<ScheduleRequest> schedules;
    private List<ScheduleDayRequest> scheduleDays;

    @Data
    public static class ScheduleDayRequest {
        private String dayOfWeek;
        private String startTime;
        private String endTime;
        private Long roomId;
        private String roomNumber;
    }

    /** Legacy API: kundalik jadval (string maydonlar, xona ixtiyoriy) */
    @Data
    public static class ScheduleRequest {
        private String dayOfWeek;
        private String startTime;
        private String endTime;
        private Long roomId;
        private String roomNumber;
    }
}
