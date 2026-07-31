package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StaffMemberMetricsDto {
    private Long userId;
    private String fullName;
    private String role;
    private String photoUrl;

    private Long leadsAssigned;
    private Long leadsConverted;
    private Double conversionRate;
    private Long paymentsReceived;
    private BigDecimal paymentsAmount;
    private Long studentsCreated;

    // TEACHER only
    private Integer groupCount;
    private Integer studentCount;
    private Double attendanceRate;
    private Double paymentRate;
    private Double onTimePaymentRate;
    private Double retentionRate;
    private Double overallScore;
    private Long attendanceMarkedCount;
    private Long unlockRequestCount;

    private Boolean insufficientData;
    /** UI: "Faoliyat yo'q" */
    private String activityLabel;
}
