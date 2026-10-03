package com.crm.dashboard;

import com.crm.dto.response.TeacherKpiScoresDto;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Analitika DTO lari (docs/design/phase6-api.md §1–§2). Raqamlar direktor dashboardi servislaridan
 * ({@link FunnelMetricsService}, {@link RetentionMetricsService}, {@link OperatorMetricsService},
 * {@link AttendanceMetricsService}) va moliya hisobotidan ({@code FinanceService}) olinadi.
 */
public final class AnalyticsDtos {

    private AnalyticsDtos() {
    }

    public enum GroupBy { DAY, WEEK, MONTH }

    // ── §1 umumiy ko'rinish ─────────────────────────────────────────────

    public record PeriodInfo(LocalDate from, LocalDate to, GroupBy groupBy,
                             LocalDate previousFrom, LocalDate previousTo, List<LocalDate> buckets) {
    }

    /**
     * Ko'rsatkich: davr qiymati, oldingi teng davr qiymati, o'zgarish % (oldingi 0 bo'lsa null) va
     * {@code buckets} bilan bir xil uzunlikdagi vaqt qatori.
     */
    public record Metric(BigDecimal value, BigDecimal previous, BigDecimal changePercent, List<BigDecimal> series) {
    }

    public record Finance(Metric income, Map<String, BigDecimal> incomeByMethod, Metric expenses,
                          Metric payrollPaid, Metric examFees, Metric net) {
    }

    public record Students(Metric newStudents, Metric exits, Map<String, Long> exitsByReason, Metric activeAtEnd) {
    }

    public record LeadSource(String source, long leads, long converted, BigDecimal conversionRate, long firstPayments) {
    }

    public record Leads(Metric created, Metric converted, BigDecimal conversionRate, BigDecimal previousConversionRate,
                        List<LeadSource> bySource) {
    }

    public record GroupFill(Long groupId, String groupName, String courseName, String teacherName,
                            long students, Integer maxStudents, BigDecimal fillPercent) {
    }

    public record CourseStudents(Long courseId, String courseName, long groups, long students) {
    }

    public record Groups(long activeGroups, BigDecimal averageFillPercent, List<GroupFill> groups,
                         List<CourseStudents> byCourse) {
    }

    public record Overview(PeriodInfo period, Finance finance, Students students, Leads leads, Groups groups) {
    }

    // ── §2 xodimlar ─────────────────────────────────────────────────────

    public enum StaffRole { TEACHER, SALES, ADMIN }

    public record Discipline(long planned, long taken, long takenOnTime, long missing, BigDecimal rate) {
    }

    public record TeacherMetrics(TeacherKpiScoresDto kpi, long activeStudents, Discipline attendance,
                                 long exits, Map<String, Long> exitsByReason) {
    }

    public record SalesMetrics(long assigned, long converted, BigDecimal conversionRate,
                               DirectorDtos.FirstResponse firstResponse, long noResponse,
                               DirectorDtos.TaskStats tasks, long firstPayments) {
    }

    public record AdminMetrics(long newStudents, DirectorDtos.CountAmount paymentsReceived) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StaffRow(int rank, Long userId, Long teacherId, String fullName, String role,
                           TeacherMetrics teacher, SalesMetrics sales, AdminMetrics admin,
                           Map<String, String> links) {
    }

    public record StaffPerformance(StaffRole role, LocalDate from, LocalDate to, List<StaffRow> rows) {
    }
}
