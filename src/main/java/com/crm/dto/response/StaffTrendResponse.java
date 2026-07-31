package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StaffTrendResponse {
    private Long userId;
    private String period;
    private List<StaffTrendPointDto> points;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StaffTrendPointDto {
        private String label;
        private Long leadsConverted;
        private BigDecimal paymentsAmount;
        private Double overallScore;
        private Boolean insufficientData;
    }
}
