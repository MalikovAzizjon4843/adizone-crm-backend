package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeacherKpiRankingItemDto {
    private int rank;
    private Long teacherId;
    private String teacherName;
    private String photoUrl;
    private int groupCount;
    private int studentCount;
    private Double attendanceRate;
    private Double paymentRate;
    private Double onTimePaymentRate;
    private Double retentionRate;
    private Double overallScore;
    private Boolean insufficientData;
}
