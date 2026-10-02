package com.crm.dashboard;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Direktor dashboardi DTO lari (director-dashboard §4, docs/design/director-dashboard-api.md).
 * Bo'lim xulosalari va drill-down qatorlari; xulosa raqami = shu bucket qatorlari soni.
 */
public final class DirectorDtos {

    private DirectorDtos() {
    }

    public record CountAmount(long count, BigDecimal amount) {
        public static CountAmount zero() {
            return new CountAmount(0, BigDecimal.ZERO);
        }
    }

    public record CountPercent(long count, BigDecimal percent) {
    }

    // ── funnel (§1.1) ───────────────────────────────────────────────────

    public record FirstPayments(long total, long fromLeads, long walkIn) {
    }

    public record FunnelActivity(long leadsCreated, long contacted, long visited, long converted,
                                 long rejected, FirstPayments firstPayments) {
    }

    public record CohortStep(String step, long reached, BigDecimal rate, BigDecimal medianDaysToStep) {
    }

    public record FunnelCohort(long size, List<CohortStep> steps) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FunnelSection(FunnelActivity activity, FunnelCohort cohort) {
    }

    public record FunnelLeadRow(Long leadId, String fullName, String phone, String source,
                                LocalDateTime createdAt, LocalDateTime stepAt, String currentStage,
                                Long assignedUserId, String assignedUser, Long studentId,
                                LocalDate firstPaymentDate, BigDecimal firstPaymentAmount) {
    }

    // ── collections (§1.2) ──────────────────────────────────────────────

    public record CollectionsSection(CountAmount due, CountAmount collected, CountAmount onTime,
                                     CountAmount late, CountAmount pending, CountAmount unpaid,
                                     BigDecimal collectionRate, BigDecimal avgDelayDays,
                                     BigDecimal avgLateDelayDays, CountAmount collectedOnDay,
                                     BigDecimal cashIn, boolean estimated) {
    }

    public record CollectionRow(Long periodId, Long studentGroupId, Long studentId, String studentName,
                                String phone, String groupName, LocalDate periodStart, LocalDate periodEnd,
                                LocalDate dueDate, LocalDate graceUntil, BigDecimal amountDue,
                                LocalDate paidOn, LocalDateTime paidAt, Long delayDays, String bucket,
                                String coverageSource, boolean estimated) {
    }

    // ── debtors (§1.3) ──────────────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DebtorsSection(Long count, BigDecimal amount, Long overdue7Plus, Long newToday,
                                 Long clearedToday, BigDecimal closedDebt, String source,
                                 List<Long> studentIds) {
        /** API javobi — snapshot uchun saqlangan ro'yxatsiz. */
        public DebtorsSection publicView() {
            return new DebtorsSection(count, amount, overdue7Plus, newToday, clearedToday, closedDebt, source, null);
        }
    }

    // ── attendance (§1.4) ───────────────────────────────────────────────

    public record AttendanceSection(long planned, long taken, long takenOnTime, long missing,
                                    long unplanned, BigDecimal rate, long upcomingToday) {
    }

    public record LessonRow(Long groupId, String groupName, Long teacherId, String teacherName,
                            LocalDate date, String startTime, String endTime, int activeStudents,
                            long markedCount, LocalDateTime firstMarkedAt, String status) {
    }

    // ── trials (§1.5) ───────────────────────────────────────────────────

    /** {@code estimated} — kogortada backfill qilingan (taxminiy) sinov sanalari bor. */
    public record TrialsSection(long cohort, long noShow, Map<String, CountPercent> buckets,
                                BigDecimal conversionRate, BigDecimal stayed30, boolean estimated) {
    }

    public record TrialRow(Long studentGroupId, Long studentId, String studentName, String phone,
                           String groupName, LocalDate trialStartedAt, LocalDate convertedDay, Integer days,
                           String bucket, String outcome, Long leadId, boolean estimated) {
    }

    // ── retention (§1.6) ────────────────────────────────────────────────

    public record RetentionCohort(String month, long size, List<BigDecimal> retained) {
    }

    public record ExitSummary(long churned, long graduated, long paused, long transferred,
                              Map<String, Long> byReason) {
    }

    public record RetentionSection(List<RetentionCohort> cohorts, ExitSummary exits) {
    }

    public record ExitRow(Long studentId, String studentName, Long studentGroupId, String groupName,
                          LocalDate exitDate, String reasonCode, String notes, long monthsStudied,
                          String kind) {
    }

    // ── operators (§1.7) ────────────────────────────────────────────────

    public record FirstResponse(Long medianMinutes, Long p90Minutes, BigDecimal within15m, BigDecimal within1h) {
    }

    public record TaskStats(long done, long doneLate, long overdueOpen) {
    }

    public record Activity(long statusChanges, long comments, LocalDateTime lastSeenAt) {
    }

    public record OperatorRow(Long userId, String fullName, String role, long assigned,
                              FirstResponse firstResponse, long noResponse, TaskStats tasks,
                              long converted, BigDecimal conversionRate, long firstPayments,
                              CountAmount paymentsReceived, Activity activity, boolean estimated) {
    }

    public record OperatorsSection(long activeOperators, long assigned, Long medianFirstResponseMin,
                                   long noResponse, long tasksDone, long tasksOverdueOpen,
                                   List<OperatorRow> top, boolean estimated) {
    }

    public record OperatorLeadRow(Long leadId, String fullName, String phone, LocalDateTime assignedAt,
                                  LocalDateTime firstResponseAt, Long responseMinutes, String firstResponseKind,
                                  String currentStage, LocalDateTime convertedAt, String metric) {
    }

    public record OperatorTaskRow(Long taskId, String title, String type, Long leadId, Long studentId,
                                  LocalDateTime dueAt, LocalDateTime completedAt, String result, String state) {
    }
}
