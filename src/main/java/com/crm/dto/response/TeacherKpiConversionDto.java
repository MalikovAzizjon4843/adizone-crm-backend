package com.crm.dto.response;

import lombok.Data;

@Data
public class TeacherKpiConversionDto {
    private long newStudents;
    private long paidStudents;
    private double conversionRate;
}
