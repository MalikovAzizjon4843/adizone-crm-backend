package com.crm.dashboard;

import com.crm.dashboard.AnalyticsDtos.AdminMetrics;
import com.crm.dashboard.AnalyticsDtos.Discipline;
import com.crm.dashboard.AnalyticsDtos.SalesMetrics;
import com.crm.dashboard.AnalyticsDtos.StaffPerformance;
import com.crm.dashboard.AnalyticsDtos.StaffRole;
import com.crm.dashboard.AnalyticsDtos.StaffRow;
import com.crm.dashboard.AnalyticsDtos.TeacherMetrics;
import com.crm.dashboard.DirectorDtos.AttendanceSection;
import com.crm.dashboard.DirectorDtos.ExitRow;
import com.crm.dashboard.DirectorDtos.OperatorRow;
import com.crm.dto.response.TeacherKpiScoresDto;
import com.crm.entity.StudentGroup;
import com.crm.entity.Teacher;
import com.crm.exception.CodedException;
import com.crm.service.TeacherKpiService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Xodimlar samaradorligi ({@code GET /api/analytics/staff?role=}, docs/design/phase6-api.md §2). Yangi hisob yo'q:
 * o'qituvchi — {@link TeacherKpiService} (4 ko'rsatkich) + {@link AttendanceMetricsService} (davomat intizomi) +
 * {@link RetentionMetricsService#exits}; sotuv va admin — {@link OperatorMetricsService#operators}
 * (direktor dashboardidagi operatorlar jadvali bilan aynan bir xil raqamlar).
 */
@Service
@RequiredArgsConstructor
public class StaffPerformanceService {

    private final AnalyticsOverviewService overview;
    private final TeacherKpiService kpiService;
    private final AttendanceMetricsService attendance;
    private final RetentionMetricsService retention;
    private final OperatorMetricsService operators;
    private final DashboardQueries queries;
    private final Clock billingClock;

    public static StaffRole parseRole(String raw) {
        try {
            return StaffRole.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw CodedException.badRequest("analytics.staff.roleInvalid", raw);
        }
    }

    @Transactional(readOnly = true)
    public StaffPerformance staff(StaffRole role, LocalDate from, LocalDate to) {
        AnalyticsOverviewService.Range r = overview.range(from, to, "MONTH");
        DashboardPeriod p = DashboardPeriod.of("CUSTOM", null, r.from(), r.to(), LocalDateTime.now(billingClock));
        List<StaffRow> rows = switch (role) {
            case TEACHER -> teachers(p);
            case SALES -> sales(p);
            case ADMIN -> admins(p);
        };
        return new StaffPerformance(role, r.from(), r.to(), rows);
    }

    // ── o'qituvchilar ───────────────────────────────────────────────────

    private List<StaffRow> teachers(DashboardPeriod p) {
        List<Teacher> teachers = queries.em().createQuery("""
                SELECT t FROM Teacher t LEFT JOIN FETCH t.user WHERE t.status <> :inactive
                """, Teacher.class).setParameter("inactive", Teacher.STATUS_INACTIVE).getResultList();
        List<Long> ids = teachers.stream().map(Teacher::getId).toList();
        Map<Long, TeacherKpiScoresDto> kpi = kpiService.computeScoresForTeachers(ids, p.from(), p.to());

        List<StudentGroup> sgs = queries.em().createQuery(
            "SELECT sg FROM StudentGroup sg JOIN FETCH sg.group g LEFT JOIN FETCH g.teacher", StudentGroup.class)
            .getResultList();
        Map<Long, Set<Long>> active = new HashMap<>();
        Map<Long, Long> teacherOfSg = new HashMap<>();
        for (StudentGroup sg : sgs) {
            Long tid = sg.getGroup().getTeacher() != null ? sg.getGroup().getTeacher().getId() : null;
            if (tid == null) {
                continue;
            }
            teacherOfSg.put(sg.getId(), tid);
            if (AnalyticsOverviewService.activeAt(sg, p.to())) {
                active.computeIfAbsent(tid, k -> new HashSet<>()).add(sg.getStudent().getId());
            }
        }
        Map<Long, Map<String, Long>> exits = new HashMap<>();
        for (ExitRow e : retention.exits(p)) {
            Long tid = teacherOfSg.get(e.studentGroupId());
            if (tid != null && "CHURN".equals(e.kind())) {
                exits.computeIfAbsent(tid, k -> new TreeMap<>()).merge(e.reasonCode(), 1L, Long::sum);
            }
        }

        List<StaffRow> rows = new ArrayList<>();
        for (Teacher t : teachers) {
            AttendanceSection a = attendance.summary(p, t.getId());
            Map<String, Long> byReason = exits.getOrDefault(t.getId(), Map.of());
            TeacherMetrics m = new TeacherMetrics(kpi.get(t.getId()), active.getOrDefault(t.getId(), Set.of()).size(),
                new Discipline(a.planned(), a.taken(), a.takenOnTime(), a.missing(), a.rate()),
                byReason.values().stream().mapToLong(Long::longValue).sum(), byReason);
            Map<String, String> links = new LinkedHashMap<>();
            links.put("kpi", "/api/teachers/" + t.getId() + "/kpi?from=" + p.from() + "&to=" + p.to());
            links.put("missingLessons", "/api/dashboard/director/attendance/lessons?status=MISSING&teacherId=" + t.getId()
                + range(p));
            links.put("exits", "/api/dashboard/director/retention/exits?kind=CHURN" + range(p));
            rows.add(new StaffRow(0, t.getUser() != null ? t.getUser().getId() : null, t.getId(),
                FunnelMetricsService.name(t.getFirstName(), t.getLastName()), "TEACHER", m, null, null, links));
        }
        rows.sort(Comparator.<StaffRow, Double>comparing(r -> score(r.teacher()), Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(r -> r.teacher().activeStudents(), Comparator.reverseOrder())
            .thenComparing(StaffRow::fullName, Comparator.nullsLast(String::compareToIgnoreCase)));
        return ranked(rows);
    }

    private static Double score(TeacherMetrics m) {
        TeacherKpiScoresDto k = m.kpi();
        return k == null || Boolean.TRUE.equals(k.getInsufficientData()) ? null : k.getOverallScore();
    }

    // ── sotuv (SM + SH) va admin ────────────────────────────────────────

    private List<StaffRow> sales(DashboardPeriod p) {
        List<StaffRow> rows = new ArrayList<>();
        for (OperatorRow o : operators.operators(p)) {
            if (!"SALES_MANAGER".equals(o.role()) && !"SALES_HEAD".equals(o.role())) {
                continue;
            }
            Map<String, String> links = new LinkedHashMap<>();
            links.put("leads", "/api/dashboard/director/operators/" + o.userId() + "/leads?metric=ASSIGNED" + range(p));
            links.put("noResponse", "/api/dashboard/director/operators/" + o.userId() + "/leads?metric=NO_RESPONSE" + range(p));
            links.put("overdueTasks", "/api/dashboard/director/operators/" + o.userId() + "/tasks?state=OVERDUE_OPEN" + range(p));
            rows.add(new StaffRow(0, o.userId(), null, o.fullName(), o.role(), null,
                new SalesMetrics(o.assigned(), o.converted(), o.conversionRate(), o.firstResponse(), o.noResponse(),
                    o.tasks(), o.firstPayments()), null, links));
        }
        rows.sort(Comparator.<StaffRow>comparingLong(r -> r.sales().converted()).reversed()
            .thenComparing(r -> r.sales().firstPayments(), Comparator.reverseOrder())
            .thenComparing(r -> r.sales().conversionRate(), Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(StaffRow::fullName, Comparator.nullsLast(String::compareToIgnoreCase)));
        return ranked(rows);
    }

    private List<StaffRow> admins(DashboardPeriod p) {
        List<StaffRow> rows = new ArrayList<>();
        for (OperatorRow o : operators.operators(p)) {
            if (!"ADMIN".equals(o.role())) {
                continue;
            }
            Map<String, String> links = new LinkedHashMap<>();
            links.put("leads", "/api/dashboard/director/operators/" + o.userId() + "/leads?metric=ASSIGNED" + range(p));
            rows.add(new StaffRow(0, o.userId(), null, o.fullName(), o.role(), null, null,
                new AdminMetrics(o.firstPayments(), o.paymentsReceived()), links));
        }
        rows.sort(Comparator.<StaffRow>comparingLong(r -> r.admin().newStudents()).reversed()
            .thenComparing(r -> r.admin().paymentsReceived().amount(), Comparator.reverseOrder())
            .thenComparing(StaffRow::fullName, Comparator.nullsLast(String::compareToIgnoreCase)));
        return ranked(rows);
    }

    private static String range(DashboardPeriod p) {
        return "&period=CUSTOM&from=" + p.from() + "&to=" + p.to();
    }

    private static List<StaffRow> ranked(List<StaffRow> sorted) {
        List<StaffRow> out = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            StaffRow r = sorted.get(i);
            out.add(new StaffRow(i + 1, r.userId(), r.teacherId(), r.fullName(), r.role(), r.teacher(), r.sales(),
                r.admin(), r.links()));
        }
        return out;
    }
}
