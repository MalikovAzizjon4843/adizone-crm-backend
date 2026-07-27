package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeacherKpiTrendPointDto {
    /** monthly: "2026-07", daily: "2026-07-15" */
    private String label;
    private Double overallScore;
    private Boolean insufficientData;
}
