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
    /** Belgilanmagan + qisman belgilangan darslar soni. */
    private int missingCount;
    /** Shulardan qisman belgilanganlari (kimdir belgilangan, hammasi emas). */
    private int partialCount;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MissingDateItem {
        private LocalDate date;
        private String dayOfWeek;
        private String startTime;
        /** Shu kuni faol o'quvchilar. */
        private int activeStudents;
        /** Ulardan belgilanmaganlar — "belgilanmagan: N ta o'quvchi". */
        private int unmarkedStudents;
        /** Qisman belgilangan (aks holda dars umuman belgilanmagan). */
        private boolean partial;
    }
}
