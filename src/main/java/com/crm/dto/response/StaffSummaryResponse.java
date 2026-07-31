package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StaffSummaryResponse {
    private String period;
    private LocalDate from;
    private LocalDate to;
    private int totalStaff;
    private Map<String, Long> byRole;
    private List<TopConversionItem> topByConversion;
    private List<TopPaymentItem> topByPayments;
    private List<TopKpiItem> topByKpi;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopConversionItem {
        private Long userId;
        private String fullName;
        private Double conversionRate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopPaymentItem {
        private Long userId;
        private String fullName;
        private BigDecimal paymentsAmount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopKpiItem {
        private Long userId;
        private String fullName;
        private Double overallScore;
    }
}
