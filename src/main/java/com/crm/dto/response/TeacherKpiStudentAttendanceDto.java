package com.crm.dto.response;

import lombok.Data;

@Data
public class TeacherKpiStudentAttendanceDto {
    private double presentRate;
    private double absentUnexcusedRate;
    private double absentExcusedRate;
    private double notMarkedRate;
}
