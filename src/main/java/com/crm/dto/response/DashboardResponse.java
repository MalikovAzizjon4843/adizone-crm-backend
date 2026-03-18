package com.crm.dto.response;
import lombok.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DashboardResponse {
    private long totalStudents;
    private long activeStudents;
    private long frozenStudents;
    private long totalGroups;
    private long activeGroups;
    private long totalTeachers;
    private long debtorCount;
    private BigDecimal monthlyRevenue;
    private BigDecimal monthlyExpenses;
    private BigDecimal netProfit;
    private double attendanceRate;
    private Map<String, Long> studentsBySource;
    private List<Map<String, Object>> revenueChart;
    private List<Map<String, Object>> studentGrowthChart;
}
