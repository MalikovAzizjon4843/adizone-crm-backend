package com.crm.dashboard;

import com.crm.dashboard.DirectorDtos.ExitRow;
import com.crm.dashboard.DirectorDtos.ExitSummary;
import com.crm.dashboard.DirectorDtos.RetentionCohort;
import com.crm.dashboard.DirectorDtos.RetentionSection;
import com.crm.entity.BillingPeriod;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.ExitReasonCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Qolish (director-dashboard §1.6, §7 #11). Kogorta — birinchi pullik oy (o'quvchi bo'yicha).
 * m oyi oxirida faol: ochiq, muzlatilmagan yozilma bor yoki o'sha oyda yopilgan davri bor.
 * Chiqish turlari: CHURN, PARTIAL (boshqa guruhda o'qiyapti), GRADUATED, PAUSE (muzlatish),
 * TRANSFER.
 */
@Service
@RequiredArgsConstructor
public class RetentionMetricsService {

    public static final int MAX_K = 12;
    public static final int DEFAULT_COHORTS = 6;

    private final DashboardQueries queries;
    private final DashboardProperties properties;

    @Transactional(readOnly = true)
    public RetentionSection summary(DashboardPeriod p) {
        YearMonth last = YearMonth.from(p.asOfDate());
        return new RetentionSection(cohorts(last.minusMonths(DEFAULT_COHORTS - 1L), last, p.asOfDate()),
            exitSummary(exits(p)));
    }

    @Transactional(readOnly = true)
    public List<RetentionCohort> cohorts(YearMonth fromMonth, YearMonth toMonth, LocalDate asOf) {
        List<DashboardQueries.FirstPayment> firsts = queries.firstPaymentsBetween(
            fromMonth.atDay(1), toMonth.atEndOfMonth());
        Map<Long, List<StudentGroup>> sgsByStudent = enrollments(firsts.stream().map(DashboardQueries.FirstPayment::studentId).toList());
        Map<Long, List<BillingPeriod>> paidPeriods = paidPeriods(sgsByStudent);
        YearMonth asOfMonth = YearMonth.from(asOf);

        Map<YearMonth, List<Long>> byMonth = new TreeMap<>();
        firsts.forEach(fp -> byMonth.computeIfAbsent(YearMonth.from(fp.date()), k -> new ArrayList<>()).add(fp.studentId()));
        List<RetentionCohort> out = new ArrayList<>();
        for (YearMonth m = fromMonth; !m.isAfter(toMonth); m = m.plusMonths(1)) {
            List<Long> students = byMonth.getOrDefault(m, List.of());
            List<BigDecimal> retained = new ArrayList<>();
            for (int k = 0; k <= MAX_K; k++) {
                YearMonth target = m.plusMonths(k);
                if (target.isAfter(asOfMonth)) {
                    break;
                }
                LocalDate end = target.equals(asOfMonth) ? asOf : target.atEndOfMonth();
                long active = students.stream().filter(s -> activeAt(sgsByStudent.getOrDefault(s, List.of()),
                    paidPeriods, end, target)).count();
                retained.add(Ratios.percent(active, students.size()));
            }
            out.add(new RetentionCohort(m.toString(), students.size(), retained));
        }
        return out;
    }

    /** P ichida yopilgan yozilmalar va ularning turi. */
    @Transactional(readOnly = true)
    public List<ExitRow> exits(DashboardPeriod p) {
        List<StudentGroup> closed = queries.em().createQuery("""
                SELECT sg FROM StudentGroup sg JOIN FETCH sg.student LEFT JOIN FETCH sg.group
                WHERE (sg.isActive = false OR sg.frozenFrom IS NOT NULL)
                  AND COALESCE(sg.exitDate, sg.leaveDate) BETWEEN :f AND :t
                """, StudentGroup.class).setParameter("f", p.from()).setParameter("t", p.to()).getResultList();
        Map<Long, List<StudentGroup>> all = enrollments(closed.stream().map(sg -> sg.getStudent().getId()).toList());
        List<ExitRow> rows = new ArrayList<>();
        for (StudentGroup sg : closed) {
            LocalDate exit = sg.getExitDate() != null ? sg.getExitDate() : sg.getLeaveDate();
            ExitReasonCode code = sg.getExitReasonCode() != null ? sg.getExitReasonCode()
                : (sg.getFrozenFrom() != null ? ExitReasonCode.FROZEN : ExitReasonCode.fromLegacy(sg.getExitReason()));
            String kind = switch (code) {
                case TRANSFERRED -> "TRANSFER";
                case FROZEN, AUTO_ARCHIVE -> "PAUSE";
                case GRADUATED -> "GRADUATED";
                default -> stillStudying(all.getOrDefault(sg.getStudent().getId(), List.of()), sg, exit)
                    ? "PARTIAL" : "CHURN";
            };
            var s = sg.getStudent();
            rows.add(new ExitRow(s.getId(), FunnelMetricsService.name(s.getFirstName(), s.getLastName()), sg.getId(),
                sg.getGroup() != null ? sg.getGroup().getGroupName() : null, exit, code.name(), sg.getExitNotes(),
                sg.getJoinDate() != null ? Math.max(0, ChronoUnit.MONTHS.between(sg.getJoinDate(), exit)) : 0, kind));
        }
        rows.sort(Comparator.comparing(ExitRow::exitDate).thenComparing(ExitRow::studentGroupId));
        return rows;
    }

    private static ExitSummary exitSummary(List<ExitRow> rows) {
        Map<String, Long> byReason = new TreeMap<>();
        rows.stream().filter(r -> "CHURN".equals(r.kind())).forEach(r -> byReason.merge(r.reasonCode(), 1L, Long::sum));
        return new ExitSummary(
            rows.stream().filter(r -> "CHURN".equals(r.kind())).map(ExitRow::studentId).distinct().count(),
            rows.stream().filter(r -> "GRADUATED".equals(r.kind())).count(),
            rows.stream().filter(r -> "PAUSE".equals(r.kind())).count(),
            rows.stream().filter(r -> "TRANSFER".equals(r.kind())).count(),
            byReason);
    }

    /** Boshqa yozilma chiqishdan keyin {@code churnGraceDays} ichida ochiq (yoki hali ochiq). */
    private boolean stillStudying(List<StudentGroup> all, StudentGroup exited, LocalDate exit) {
        LocalDate horizon = exit.plusDays(properties.getChurnGraceDays());
        return all.stream().filter(o -> !o.getId().equals(exited.getId())).anyMatch(o ->
            (o.getJoinDate() == null || !o.getJoinDate().isAfter(horizon))
                && (o.getLeaveDate() == null ? Boolean.TRUE.equals(o.getIsActive()) || o.getFrozenFrom() != null
                    : o.getLeaveDate().isAfter(exit)));
    }

    private static boolean activeAt(List<StudentGroup> sgs, Map<Long, List<BillingPeriod>> paid, LocalDate end,
                                    YearMonth month) {
        for (StudentGroup sg : sgs) {
            boolean joined = sg.getJoinDate() == null || !sg.getJoinDate().isAfter(end);
            boolean notFrozen = sg.getFrozenFrom() == null || sg.getFrozenFrom().isAfter(end);
            boolean open = sg.getLeaveDate() == null ? Boolean.TRUE.equals(sg.getIsActive()) || sg.getFrozenFrom() != null
                : sg.getLeaveDate().isAfter(end);
            if (joined && notFrozen && open) {
                return true;
            }
            for (BillingPeriod bp : paid.getOrDefault(sg.getId(), List.of())) {
                if (bp.getPaidOn() != null && YearMonth.from(bp.getPaidOn()).equals(month)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Map<Long, List<StudentGroup>> enrollments(List<Long> studentIds) {
        Map<Long, List<StudentGroup>> out = new HashMap<>();
        if (studentIds.isEmpty()) {
            return out;
        }
        queries.em().createQuery("SELECT sg FROM StudentGroup sg WHERE sg.student.id IN :ids", StudentGroup.class)
            .setParameter("ids", studentIds).getResultList()
            .forEach(sg -> out.computeIfAbsent(sg.getStudent().getId(), k -> new ArrayList<>()).add(sg));
        return out;
    }

    private Map<Long, List<BillingPeriod>> paidPeriods(Map<Long, List<StudentGroup>> sgsByStudent) {
        List<Long> sgIds = sgsByStudent.values().stream().flatMap(List::stream).map(StudentGroup::getId).toList();
        Map<Long, List<BillingPeriod>> out = new HashMap<>();
        if (sgIds.isEmpty()) {
            return out;
        }
        queries.em().createQuery("SELECT bp FROM BillingPeriod bp WHERE bp.studentGroupId IN :ids AND bp.paidOn IS NOT NULL",
                BillingPeriod.class).setParameter("ids", sgIds).getResultList()
            .forEach(bp -> out.computeIfAbsent(bp.getStudentGroupId(), k -> new ArrayList<>()).add(bp));
        return out;
    }
}
