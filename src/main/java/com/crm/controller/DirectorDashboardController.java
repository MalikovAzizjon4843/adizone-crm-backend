package com.crm.controller;

import com.crm.billing.BillingStatusService;
import com.crm.billing.DebtorService;
import com.crm.dashboard.AttendanceMetricsService;
import com.crm.dashboard.CollectionsMetricsService;
import com.crm.dashboard.DashboardPeriod;
import com.crm.dashboard.DirectorDashboardService;
import com.crm.dashboard.DirectorDashboardService.Section;
import com.crm.dashboard.DirectorDtos;
import com.crm.dashboard.FunnelMetricsService;
import com.crm.dashboard.OperatorMetricsService;
import com.crm.dashboard.RetentionMetricsService;
import com.crm.dashboard.TrialMetricsService;
import com.crm.dto.response.ApiResponse;
import com.crm.dto.response.DebtorsListResponse;
import com.crm.entity.DirectorDailyStat;
import com.crm.exception.CodedException;
import com.crm.repository.DirectorDailyStatRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Direktor dashboardi (director-dashboard §4, docs/design/director-dashboard-api.md).
 * Xulosada ruxsatsiz bo'lim null; drill-down ruxsatsiz bo'lsa 403 {@code dashboard.section.forbidden}.
 */
@RestController
@RequestMapping("/api/dashboard/director")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT')")
public class DirectorDashboardController {

    public static final int MAX_PAGE_SIZE = 200;
    public static final int MAX_TREND_DAYS = 92;

    private final DirectorDashboardService dashboard;
    private final FunnelMetricsService funnel;
    private final CollectionsMetricsService collections;
    private final AttendanceMetricsService attendance;
    private final TrialMetricsService trials;
    private final RetentionMetricsService retention;
    private final OperatorMetricsService operators;
    private final DebtorService debtorService;
    private final BillingStatusService statusService;
    private final DirectorDailyStatRepository statRepository;
    private final ObjectMapper objectMapper;

    public record PageDto<T>(List<T> items, int page, int size, long total) {
        static <T> PageDto<T> of(List<T> all, Integer page, Integer size) {
            int s = size == null ? 50 : Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
            int p = page == null ? 0 : Math.max(page, 0);
            List<T> items = all.stream().skip((long) p * s).limit(s).toList();
            return new PageDto<>(items, p, s, all.size());
        }
    }

    public record TrendPoint(LocalDate date, Object value, boolean finalized) {
    }

    // ── Xulosa ──────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<ApiResponse<DirectorDashboardService.Summary>> summary(
            @RequestParam(name = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(name = "period", required = false) String period,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "operatorId", required = false) Long operatorId,
            @RequestParam(name = "teacherId", required = false) Long teacherId,
            @RequestParam(name = "includeImported", defaultValue = "false") boolean includeImported) {
        return ResponseEntity.ok(ApiResponse.success(dashboard.summary(
            new DirectorDashboardService.Params(period, date, from, to, operatorId, teacherId, includeImported))));
    }

    // ── Drill-down ──────────────────────────────────────────────────────

    @GetMapping("/funnel/leads")
    public ResponseEntity<ApiResponse<PageDto<DirectorDtos.FunnelLeadRow>>> funnelLeads(
            @RequestParam(name = "step") String step,
            @RequestParam(name = "mode", defaultValue = "ACTIVITY") String mode,
            @RequestParam(name = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(name = "period", required = false) String period,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "operatorId", required = false) Long operatorId,
            @RequestParam(name = "includeImported", defaultValue = "false") boolean includeImported,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {
        DirectorDashboardService.requireSection(Section.FUNNEL);
        DashboardPeriod p = period(period, date, from, to);
        FunnelMetricsService.Filter f = new FunnelMetricsService.Filter(includeImported, operatorId);
        FunnelMetricsService.Step s = FunnelMetricsService.parseStep(step);
        List<DirectorDtos.FunnelLeadRow> rows = "COHORT".equalsIgnoreCase(mode)
            ? funnel.cohortRows(p, s, f) : funnel.activityRows(p, s, f);
        return ok(PageDto.of(rows, page, size));
    }

    @GetMapping("/collections/periods")
    public ResponseEntity<ApiResponse<PageDto<DirectorDtos.CollectionRow>>> collectionPeriods(
            @RequestParam(name = "bucket", defaultValue = "DUE") String bucket,
            @RequestParam(name = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(name = "period", required = false) String period,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {
        DirectorDashboardService.requireSection(Section.COLLECTIONS);
        return ok(PageDto.of(collections.rows(period(period, date, from, to),
            CollectionsMetricsService.parseBucket(bucket)), page, size));
    }

    /** billing-v2 yagona ta'rifi — {@code /api/payments/debtors} bilan aynan bir servis. */
    @GetMapping("/debtors")
    public ResponseEntity<ApiResponse<DebtorsListResponse>> debtors(
            @RequestParam(name = "scope", required = false) String scope,
            @RequestParam(name = "minDays", required = false) Integer minDays,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {
        DirectorDashboardService.requireSection(Section.DEBTORS);
        return ok(debtorService.debtors(new DebtorService.Filter(DebtorService.Scope.parse(scope), minDays, null,
            page, size != null ? size : 50), statusService.today()));
    }

    @GetMapping("/attendance/lessons")
    public ResponseEntity<ApiResponse<PageDto<DirectorDtos.LessonRow>>> lessons(
            @RequestParam(name = "status", defaultValue = "PLANNED") String status,
            @RequestParam(name = "teacherId", required = false) Long teacherId,
            @RequestParam(name = "groupId", required = false) Long groupId,
            @RequestParam(name = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(name = "period", required = false) String period,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {
        DirectorDashboardService.requireSection(Section.ATTENDANCE);
        List<DirectorDtos.LessonRow> rows = attendance.rows(period(period, date, from, to), teacherId, status).stream()
            .filter(r -> groupId == null || groupId.equals(r.groupId())).toList();
        return ok(PageDto.of(rows, page, size));
    }

    @GetMapping("/trials/enrollments")
    public ResponseEntity<ApiResponse<PageDto<DirectorDtos.TrialRow>>> trialRows(
            @RequestParam(name = "bucket") String bucket,
            @RequestParam(name = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(name = "period", required = false) String period,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {
        DirectorDashboardService.requireSection(Section.TRIALS);
        return ok(PageDto.of(trials.rows(period(period, date, from, to), TrialMetricsService.parseBucket(bucket)),
            page, size));
    }

    @GetMapping("/retention")
    public ResponseEntity<ApiResponse<List<DirectorDtos.RetentionCohort>>> retentionCohorts(
            @RequestParam(name = "cohortFrom", required = false) String cohortFrom,
            @RequestParam(name = "cohortTo", required = false) String cohortTo) {
        DirectorDashboardService.requireSection(Section.RETENTION);
        LocalDate today = statusService.today();
        YearMonth to = cohortTo != null ? parseMonth(cohortTo) : YearMonth.from(today);
        YearMonth from = cohortFrom != null ? parseMonth(cohortFrom)
            : to.minusMonths(RetentionMetricsService.DEFAULT_COHORTS - 1L);
        if (to.isBefore(from) || ChronoUnit.MONTHS.between(from, to) > 24) {
            throw CodedException.badRequest("dashboard.param.invalid", "cohortFrom/cohortTo");
        }
        return ok(retention.cohorts(from, to, today));
    }

    @GetMapping("/retention/exits")
    public ResponseEntity<ApiResponse<PageDto<DirectorDtos.ExitRow>>> exits(
            @RequestParam(name = "reasonCode", required = false) String reasonCode,
            @RequestParam(name = "kind", required = false) String kind,
            @RequestParam(name = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(name = "period", required = false) String period,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {
        DirectorDashboardService.requireSection(Section.RETENTION);
        List<DirectorDtos.ExitRow> rows = retention.exits(period(period, date, from, to)).stream()
            .filter(r -> reasonCode == null || reasonCode.equalsIgnoreCase(r.reasonCode()))
            .filter(r -> kind == null || kind.equalsIgnoreCase(r.kind())).toList();
        return ok(PageDto.of(rows, page, size));
    }

    @GetMapping("/operators")
    public ResponseEntity<ApiResponse<List<DirectorDtos.OperatorRow>>> operatorRows(
            @RequestParam(name = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(name = "period", required = false) String period,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        DirectorDashboardService.requireSection(Section.OPERATORS);
        return ok(operators.operators(period(period, date, from, to)));
    }

    @GetMapping("/operators/{userId}/leads")
    public ResponseEntity<ApiResponse<PageDto<DirectorDtos.OperatorLeadRow>>> operatorLeads(
            @PathVariable(name = "userId") Long userId,
            @RequestParam(name = "metric", defaultValue = "ASSIGNED") String metric,
            @RequestParam(name = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(name = "period", required = false) String period,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {
        DirectorDashboardService.requireSection(Section.OPERATORS);
        return ok(PageDto.of(operators.leads(period(period, date, from, to), userId,
            OperatorMetricsService.parse(OperatorMetricsService.LeadMetric.class, metric, "metric")), page, size));
    }

    @GetMapping("/operators/{userId}/tasks")
    public ResponseEntity<ApiResponse<PageDto<DirectorDtos.OperatorTaskRow>>> operatorTasks(
            @PathVariable(name = "userId") Long userId,
            @RequestParam(name = "state", defaultValue = "DONE") String state,
            @RequestParam(name = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(name = "period", required = false) String period,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {
        DirectorDashboardService.requireSection(Section.OPERATORS);
        return ok(PageDto.of(operators.tasks(period(period, date, from, to), userId,
            OperatorMetricsService.parse(OperatorMetricsService.TaskState.class, state, "state")), page, size));
    }

    /** {@code metric=<bo'lim>.<maydon yo'li>}, masalan {@code collections.due.count} — kunlik snapshotlardan. */
    @GetMapping("/trend")
    public ResponseEntity<ApiResponse<List<TrendPoint>>> trend(
            @RequestParam(name = "metric") String metric,
            @RequestParam(name = "from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        String[] path = metric.split("\\.");
        if (path.length < 2) {
            throw CodedException.badRequest("dashboard.param.invalid", "metric");
        }
        Section section = DirectorDashboardService.parseSection(path[0]);
        DirectorDashboardService.requireSection(section);
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) + 1 > MAX_TREND_DAYS) {
            throw CodedException.badRequest("dashboard.period.invalid", MAX_TREND_DAYS);
        }
        List<TrendPoint> points = new ArrayList<>();
        for (DirectorDailyStat st : statRepository.findBySectionAndStatDateBetweenOrderByStatDateAsc(section.key, from, to)) {
            try {
                JsonNode node = objectMapper.readTree(st.getPayload());
                for (int i = 1; i < path.length && node != null; i++) {
                    node = node.get(path[i]);
                }
                Object value = node == null || node.isNull() ? null
                    : node.isNumber() ? node.numberValue() : node.isTextual() ? node.asText() : node.toString();
                points.add(new TrendPoint(st.getStatDate(), value, st.isFinalized()));
            } catch (Exception e) {
                points.add(new TrendPoint(st.getStatDate(), null, st.isFinalized()));
            }
        }
        return ok(points);
    }

    // ── yordamchilar ────────────────────────────────────────────────────

    private DashboardPeriod period(String period, LocalDate date, LocalDate from, LocalDate to) {
        return dashboard.period(new DirectorDashboardService.Params(period, date, from, to, null, null, false));
    }

    private static YearMonth parseMonth(String raw) {
        try {
            return YearMonth.parse(raw.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw CodedException.badRequest("dashboard.param.invalid", "month");
        }
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T body) {
        return ResponseEntity.ok(ApiResponse.success(body));
    }
}
