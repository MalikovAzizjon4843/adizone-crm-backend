package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardStatsDto {
    private long newOrders;
    private long firstLessonStudents;
    private long newStudents;
    private long activeStudents;
    private long leftFromOrder;
    private long leftFromActive;
    private long newLeftStudents;
    private long debtors;
    private long groups;
    private long firstPaymentStudents;
    private long frozen;
    private long archived;
}
