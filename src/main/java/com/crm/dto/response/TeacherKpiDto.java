package com.crm.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class TeacherKpiDto {
    private Long teacherId;
    private String teacherName;
    private BigDecimal balance;
    private BigDecimal bonus;
    private BigDecimal advance;
    private BigDecimal penalty;
    private double studentProgress;
    private List<TeacherKpiGroupDto> groups;
    private TeacherKpiConversionDto conversion;
    private TeacherKpiAttendanceDto teacherAttendance;
    private TeacherKpiStudentAttendanceDto studentAttendance;
    private TeacherKpiSatisfactionDto satisfaction;
}
