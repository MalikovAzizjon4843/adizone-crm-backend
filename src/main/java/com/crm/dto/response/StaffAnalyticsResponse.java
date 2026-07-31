package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StaffAnalyticsResponse {
    private String period;
    private LocalDate from;
    private LocalDate to;
    private List<StaffMemberMetricsDto> staff;
}
