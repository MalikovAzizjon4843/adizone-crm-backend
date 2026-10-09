package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * {@code GET /api/leads/stats/without-task} — yopilmagan, ochiq vazifasi yo'q lidlar
 * (amoCRM "Без задач"): bosqich bo'yicha soni va sahifali ro'yxat.
 *
 * <p>{@code byStage} — barcha OPEN bosqichlar voronka tartibida (bo'shlari 0 bilan);
 * {@code requiresTask = true} qatorlar — qoida buzilgan lidlar ({@code taskMissing}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeadWithoutTaskStatsResponse {

    /** {@code byStage} yig'indisi. */
    private long total;
    /** Shulardan {@code requiresTask} bosqichlarda turganlari. */
    private long requiredTotal;
    private List<StageCount> byStage;
    private PageResponse<LeadResponse> leads;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StageCount {
        private String status;
        private String statusLabel;
        private boolean requiresTask;
        private long count;
    }
}
