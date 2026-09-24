package com.crm.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class GroupRequest {
    @NotBlank(message = "{group.groupName.required}")
    private String groupName;
    @NotNull(message = "{group.courseId.required}")
    private Long courseId;
    private Long teacherId;
    private String room;
    /** Default classroom for the group (fallback for schedule days / auto-timetable). */
    private Long classroomId;
    @Min(value = 1, message = "{group.maxStudents.min}")
    private Integer maxStudents = 20;
    @NotNull(message = "{group.startDate.required}")
    private LocalDate startDate;
    private LocalDate endDate;
    private String notes;
    /**
     * Ixtiyoriy: FORMING, ACTIVE, COMPLETED, CANCELLED (registr muhim emas).
     * Yaratishda berilmasa ACTIVE; tahrirlashda berilmasa o'zgarmaydi.
     * String — noto'g'ri qiymat Jackson xatosi emas, tushunarli 400 bo'lsin.
     */
    private String status;
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
