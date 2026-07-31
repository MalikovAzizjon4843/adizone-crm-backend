package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MissingAttendanceResponse {
    private Long groupId;
    private String groupName;
    private List<MissingDateItem> missingDates;
    private int missingCount;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MissingDateItem {
        private LocalDate date;
        private String dayOfWeek;
        private String startTime;
    }
}
