package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** {@code GET /api/homework/{id}/students} — guruh o'quvchilari bo'yicha holat (phase6-api §4). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HomeworkRosterResponse {
    private HomeworkResponse homework;
    private Summary summary;
    private List<Row> students;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Summary {
        private int total;
        private int submitted;
        private int late;
        private int notSubmitted;
        private int graded;
        /** Baholanganlar o'rtachasi; baho yo'q — null. */
        private BigDecimal averageMark;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Row {
        private Long studentId;
        private String studentName;
        /** false — guruhdan chiqqan, lekin oldin belgilangan. */
        private boolean inGroup;
        private Long submissionId;
        private String status;
        private BigDecimal marksObtained;
        private String remarks;
        private LocalDateTime submittedAt;
        private String fileUrl;
        private LocalDateTime updatedAt;
    }
}
