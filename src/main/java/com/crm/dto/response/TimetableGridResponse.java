package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimetableGridResponse {
    private String dayOfWeek;
    private String startTime;
    private String endTime;
    private int slotMinutes;

    @Builder.Default
    private List<TimetableGridRoomDto> rooms = new ArrayList<>();

    @Builder.Default
    private List<TimetableGridEntryDto> entries = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimetableGridRoomDto {
        private Long id;
        private String name;
        private Integer capacity;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimetableGridEntryDto {
        private Long id;
        private Long roomId;
        private Long groupId;
        private String groupName;
        private String courseName;
        private String teacherName;
        private String startTime;
        private String endTime;
        private String color;
    }
}
