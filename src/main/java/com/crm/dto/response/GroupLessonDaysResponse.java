package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupLessonDaysResponse {
    private Long groupId;
    private String groupName;
    private List<LessonDayItem> days;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LessonDayItem {
        private String dayOfWeek;
        private String startTime;
        private String endTime;
        private String roomName;
    }
}
