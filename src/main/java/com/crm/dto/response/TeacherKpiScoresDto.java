package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeacherKpiScoresDto {
    private Double attendanceRate;
    private Double paymentRate;
    private Double onTimePaymentRate;
    private Double retentionRate;
    /** null agar ma'lumot yetarli emas */
    private Double overallScore;
    private Boolean insufficientData;
}
