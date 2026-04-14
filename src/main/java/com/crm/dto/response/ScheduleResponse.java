package com.crm.dto.response;
import lombok.*;
import java.time.DayOfWeek;
import java.time.LocalTime;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ScheduleResponse {
    private Long id;
    private DayOfWeek dayOfWeek;
    private LocalTime startTime;
    private LocalTime endTime;
    private Long roomId;
    private String roomNumber;
}
