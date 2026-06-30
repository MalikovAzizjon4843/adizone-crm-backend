package com.crm.dto.response;

import lombok.Data;

@Data
public class TeacherKpiSatisfactionDto {
    private int veryUnhappy;
    private int unhappy;
    private int neutral;
    private int satisfied;
    private int verySatisfied;
}
