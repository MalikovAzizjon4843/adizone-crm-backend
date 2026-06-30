package com.crm.dto.response;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class TeacherKpiAttendanceDto {
    private long plannedLessons;
    private long conductedLessons;
    private long missedLessons;
    private BigDecimal penaltyAmount;
}
