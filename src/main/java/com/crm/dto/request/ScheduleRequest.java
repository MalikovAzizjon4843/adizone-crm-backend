package com.crm.dto.request;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.time.DayOfWeek;
import java.time.LocalTime;
@Data
public class ScheduleRequest {
    @NotNull private DayOfWeek dayOfWeek;
    @NotNull private LocalTime startTime;
    @NotNull private LocalTime endTime;
}
