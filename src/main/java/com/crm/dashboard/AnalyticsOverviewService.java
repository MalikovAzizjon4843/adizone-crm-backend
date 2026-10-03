package com.crm.dashboard;

import com.crm.dashboard.AnalyticsDtos.CourseStudents;
import com.crm.dashboard.AnalyticsDtos.Finance;
import com.crm.dashboard.AnalyticsDtos.GroupBy;
import com.crm.dashboard.AnalyticsDtos.GroupFill;
import com.crm.dashboard.AnalyticsDtos.Groups;
import com.crm.dashboard.AnalyticsDtos.LeadSource;
import com.crm.dashboard.AnalyticsDtos.Leads;
import com.crm.dashboard.AnalyticsDtos.Metric;
import com.crm.dashboard.AnalyticsDtos.Overview;
import com.crm.dashboard.AnalyticsDtos.PeriodInfo;
import com.crm.dashboard.AnalyticsDtos.Students;
import com.crm.dashboard.DirectorDtos.ExitRow;
import com.crm.dto.response.FinanceReportResponse;
import com.crm.entity.Group;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.PaymentStatus;
import com.crm.entity.enums.PayrollStatus;
import com.crm.exception.CodedException;
import com.crm.repository.CashTransactionRepository;
import com.crm.service.FinanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
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
import java.util.function.Function;

/**
 * Analitika — umumiy ko'rinish ({@code GET /api/analytics/overview}, docs/design/phase6-api.md §1).
 *
 * <p>Takrorlash yo'q: jami moliya — {@link FinanceService#getFinanceReport} (moliya hisoboti bilan aynan bir xil
 * raqam), yangi o'quvchi — birinchi real to'lov ({@link DashboardQueries#firstPaymentsBetween}), ketganlar —
 * {@link RetentionMetricsService#exits} ({@code CHURN}), lidlar — {@link FunnelMetricsService#cohortLeadFacts}.
 * Vaqt qatorlari shu manbalarning bucket bo'yicha bo'linishi; yig'indisi jami bilan teng.
 */
@Service
@RequiredArgsConstructor
public class AnalyticsOverviewService {

    /** DAY bo'yicha guruhlashda eng ko'p kun (qator uzunligi). */
    public static final int MAX_DAY_BUCKETS = 92;

    private final FinanceService financeService;
    private final CashTransactionRepository cashTransactionRepository;
    private final DashboardQueries queries;
    private final FunnelMetricsService funnel;
    private final RetentionMetricsService retention;
    private final Clock billingClock;

    /** Davr va guruhlash — tekshirilgan; {@code from/to} berilmasa joriy oy boshidan bugungacha. */
    public record Range(LocalDate from, LocalDate to, GroupBy groupBy) {
    }

    public Range range(LocalDate from, LocalDate to, String groupBy) {
        LocalDate today = LocalDate.now(billingClock);
        LocalDate f = from != null ? from : (to != null ? to : today).withDayOfMonth(1);
        LocalDate t = to != null ? to : (from != null && from.isAfter(today) ? from : today);
        if (t.isBefore(f) || ChronoUnit.DAYS.between(f, t) + 1 > DashboardPeriod.MAX_CUSTOM_DAYS) {
            throw CodedException.badRequest("analytics.period.invalid", DashboardPeriod.MAX_CUSTOM_DAYS);
        }
        long days = ChronoUnit.DAYS.between(f, t) + 1;
        GroupBy g;
        if (groupBy == null || groupBy.isBlank()) {
            g = days <= 31 ? GroupBy.DAY : days <= 120 ? GroupBy.WEEK : GroupBy.MONTH;
        } else {
            try {
                g = GroupBy.valueOf(groupBy.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw CodedException.badRequest("analytics.groupBy.invalid", groupBy);
            }
        }
        if (g == GroupBy.DAY && days > MAX_DAY_BUCKETS) {
            throw CodedException.badRequest("analytics.groupBy.tooFine", MAX_DAY_BUCKETS);
        }
        return new Range(f, t, g);
    }

    @Transactional(readOnly = true)
    public Overview overview(LocalDate from, LocalDate to, String groupBy) {
        Range r = range(from, to, groupBy);
        long days = ChronoUnit.DAYS.between(r.from(), r.to()) + 1;
        LocalDate prevTo = r.from().minusDays(1);
        LocalDate prevFrom = r.from().minusDays(days);
        List<LocalDate> buckets = buckets(r.from(), r.to(), r.groupBy());
        Function<LocalDate, Integer> idx = d -> bucketIndex(buckets, d);

        Snapshot cur = snapshot(r.from(), r.to(), buckets, idx);
        Snapshot prev = snapshot(prevFrom, prevTo, null, null);

        Finance finance = new Finance(
            metric(cur.income, prev.income, cur.incomeSeries),
            cur.incomeByMethod,
            metric(cur.expenses, prev.expenses, cur.expenseSeries),
            metric(cur.payroll, prev.payroll, cur.payrollSeries),
            metric(cur.examFees, prev.examFees, cur.examFeeSeries),
            metric(cur.net(), prev.net(), cur.netSeries()));
        Students students = new Students(
            metric(cur.newStudents, prev.newStudents, cur.newStudentSeries),
            metric(cur.exits, prev.exits, cur.exitSeries),
            cur.exitsByReason,
            metric(cur.activeAtEnd, prev.activeAtEnd, cur.activeSeries));
        Leads leads = new Leads(
            metric(cur.leads, prev.leads, cur.leadSeries),
            metric(cur.converted, prev.converted, cur.convertedSeries),
            Ratios.percent(cur.converted.longValue(), cur.leads.longValue()),
            Ratios.percent(prev.converted.longValue(), prev.leads.longValue()),
            cur.bySource);
        return new Overview(new PeriodInfo(r.from(), r.to(), r.groupBy(), prevFrom, prevTo, buckets),
            finance, students, leads, groups(r.to()));
    }

    // ── bir davr ────────────────────────────────────────────────────────

    /** Bir davrning jami qiymatlari; {@code buckets} null bo'lsa qatorlar hisoblanmaydi (oldingi davr). */
    private Snapshot snapshot(LocalDate from, LocalDate to, List<LocalDate> buckets, Function<LocalDate, Integer> idx) {
        Snapshot s = new Snapshot(buckets == null ? 0 : buckets.size());
        FinanceReportResponse report = financeService.getFinanceReport(from, to);
        s.income = nz(report.getTotalIncome());
        s.expenses = nz(report.getTotalExpenses());
        s.payroll = nz(report.getPayrollPaid());
        s.examFees = nz(report.getExamFees());

        DashboardPeriod p = DashboardPeriod.of("CUSTOM", null, from, to, LocalDateTime.now(billingClock));
        List<DashboardQueries.FirstPayment> firsts = queries.firstPaymentsBetween(from, to);
        s.newStudents = BigDecimal.valueOf(firsts.size());
        List<ExitRow> churn = retention.exits(p).stream().filter(e -> "CHURN".equals(e.kind())).toList();
        s.exits = BigDecimal.valueOf(churn.stream().map(ExitRow::studentId).distinct().count());
        List<FunnelMetricsService.CohortLead> cohort = funnel.cohortLeadFacts(p, FunnelMetricsService.Filter.defaults());
        s.leads = BigDecimal.valueOf(cohort.size());
        s.converted = BigDecimal.valueOf(cohort.stream().filter(FunnelMetricsService.CohortLead::converted).count());
        List<StudentGroup> enrollments = allEnrollments();
        s.activeAtEnd = BigDecimal.valueOf(activeStudentsAt(enrollments, to));
        if (buckets == null) {
            return s;
        }

        // Qatorlar — jami bilan bir xil manba, bucket bo'yicha
        for (Object[] row : queries.em().createQuery("""
                SELECT p.paymentDate, p.paymentMethod, COALESCE(SUM(COALESCE(p.cashAmount, p.amount)), 0) FROM Payment p
                WHERE p.paymentDate BETWEEN :f AND :t AND p.status = :paid
                GROUP BY p.paymentDate, p.paymentMethod
                """, Object[].class).setParameter("f", from).setParameter("t", to)
            .setParameter("paid", PaymentStatus.PAID).getResultList()) {
            BigDecimal amount = nz((BigDecimal) row[2]);
            add(s.incomeSeries, idx.apply((LocalDate) row[0]), amount);
            s.incomeByMethod.merge(row[1] != null ? row[1].toString() : "UNKNOWN", amount, BigDecimal::add);
        }
        for (Object[] row : queries.em().createQuery("""
                SELECT e.expenseDate, SUM(e.amount) FROM Expense e WHERE e.expenseDate BETWEEN :f AND :t
                GROUP BY e.expenseDate
                """, Object[].class).setParameter("f", from).setParameter("t", to).getResultList()) {
            add(s.expenseSeries, idx.apply((LocalDate) row[0]), nz((BigDecimal) row[1]));
        }
        for (Object[] row : queries.em().createQuery("""
                SELECT p.paidAt, p.netSalary FROM Payroll p
                WHERE p.status = :paid AND p.paidAt >= :a AND p.paidAt < :b
                """, Object[].class).setParameter("paid", PayrollStatus.PAID)
            .setParameter("a", from.atStartOfDay()).setParameter("b", to.plusDays(1).atStartOfDay()).getResultList()) {
            add(s.payrollSeries, idx.apply(((LocalDateTime) row[0]).toLocalDate()), nz((BigDecimal) row[1]));
        }
        for (int i = 0; i < buckets.size(); i++) {
            LocalDate end = i + 1 < buckets.size() ? buckets.get(i + 1).minusDays(1) : to;
            s.examFeeSeries.set(i, nz(cashTransactionRepository.sumExamFees(buckets.get(i), end)));
            s.activeSeries.set(i, BigDecimal.valueOf(activeStudentsAt(enrollments, end)));
        }
        firsts.forEach(fp -> add(s.newStudentSeries, idx.apply(fp.date()), BigDecimal.ONE));
        Map<Long, LocalDate> firstExit = new HashMap<>();
        churn.forEach(e -> firstExit.merge(e.studentId(), e.exitDate(), (a, b) -> a.isBefore(b) ? a : b));
        firstExit.values().forEach(d -> add(s.exitSeries, idx.apply(d), BigDecimal.ONE));
        churn.forEach(e -> s.exitsByReason.merge(e.reasonCode(), 1L, Long::sum));
        Map<String, long[]> bySource = new TreeMap<>();
        for (FunnelMetricsService.CohortLead l : cohort) {
            int i = idx.apply(l.createdAt().toLocalDate());
            add(s.leadSeries, i, BigDecimal.ONE);
            long[] c = bySource.computeIfAbsent(l.source() != null && !l.source().isBlank() ? l.source() : "UNKNOWN",
                k -> new long[3]);
            c[0]++;
            if (l.converted()) {
                add(s.convertedSeries, i, BigDecimal.ONE);
                c[1]++;
            }
            if (l.firstPayment()) {
                c[2]++;
            }
        }
        bySource.forEach((src, c) -> s.bySource.add(new LeadSource(src, c[0], c[1], Ratios.percent(c[1], c[0]), c[2])));
        s.bySource.sort(Comparator.comparingLong(LeadSource::leads).reversed().thenComparing(LeadSource::source));
        return s;
    }

    // ── guruhlar (holat: davr oxiri) ────────────────────────────────────

    private Groups groups(LocalDate at) {
        List<Group> active = queries.em().createQuery("""
                SELECT g FROM Group g LEFT JOIN FETCH g.course LEFT JOIN FETCH g.teacher WHERE g.status = :active
                """, Group.class).setParameter("active", GroupStatus.ACTIVE).getResultList();
        Map<Long, Set<Long>> studentsByGroup = new HashMap<>();
        for (StudentGroup sg : allEnrollments()) {
            if (sg.getGroup() != null && activeAt(sg, at)) {
                studentsByGroup.computeIfAbsent(sg.getGroup().getId(), k -> new HashSet<>()).add(sg.getStudent().getId());
            }
        }
        List<GroupFill> fills = new ArrayList<>();
        Map<Long, CourseAcc> courses = new LinkedHashMap<>();
        BigDecimal sum = BigDecimal.ZERO;
        int withCapacity = 0;
        for (Group g : active) {
            long n = studentsByGroup.getOrDefault(g.getId(), Set.of()).size();
            BigDecimal fill = g.getMaxStudents() != null && g.getMaxStudents() > 0
                ? Ratios.percent(n, g.getMaxStudents()) : null;
            if (fill != null) {
                sum = sum.add(fill);
                withCapacity++;
            }
            fills.add(new GroupFill(g.getId(), g.getGroupName(),
                g.getCourse() != null ? g.getCourse().getCourseName() : null,
                g.getTeacher() != null ? FunnelMetricsService.name(g.getTeacher().getFirstName(), g.getTeacher().getLastName()) : null,
                n, g.getMaxStudents(), fill));
            Long courseId = g.getCourse() != null ? g.getCourse().getId() : null;
            CourseAcc acc = courses.computeIfAbsent(courseId,
                k -> new CourseAcc(g.getCourse() != null ? g.getCourse().getCourseName() : null));
            acc.groups++;
            acc.students.addAll(studentsByGroup.getOrDefault(g.getId(), Set.of()));
        }
        fills.sort(Comparator.comparing(GroupFill::fillPercent, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(GroupFill::groupName, Comparator.nullsLast(String::compareToIgnoreCase)));
        List<CourseStudents> byCourse = new ArrayList<>();
        courses.forEach((id, acc) -> byCourse.add(new CourseStudents(id, acc.name, acc.groups, acc.students.size())));
        byCourse.sort(Comparator.comparingLong(CourseStudents::students).reversed()
            .thenComparing(CourseStudents::courseName, Comparator.nullsLast(String::compareToIgnoreCase)));
        BigDecimal avg = withCapacity == 0 ? null
            : sum.divide(BigDecimal.valueOf(withCapacity), 1, RoundingMode.HALF_UP);
        return new Groups(active.size(), avg, fills, byCourse);
    }

    private static final class CourseAcc {
        final String name;
        long groups;
        final Set<Long> students = new HashSet<>();

        CourseAcc(String name) {
            this.name = name;
        }
    }

    // ── faol o'quvchilar ────────────────────────────────────────────────

    private List<StudentGroup> allEnrollments() {
        return queries.em().createQuery("SELECT sg FROM StudentGroup sg LEFT JOIN FETCH sg.group", StudentGroup.class)
            .getResultList();
    }

    /** {@code d} kuni o'qiyotgan (yozilgan, chiqmagan, muzlatilmagan) yozilma — sinov ham faol hisoblanadi. */
    static boolean activeAt(StudentGroup sg, LocalDate d) {
        boolean joined = sg.getJoinDate() == null || !sg.getJoinDate().isAfter(d);
        boolean notFrozen = sg.getFrozenFrom() == null || sg.getFrozenFrom().isAfter(d);
        boolean open = sg.getLeaveDate() == null ? Boolean.TRUE.equals(sg.getIsActive()) || sg.getFrozenFrom() != null
            : sg.getLeaveDate().isAfter(d);
        return joined && notFrozen && open;
    }

    private static long activeStudentsAt(List<StudentGroup> sgs, LocalDate d) {
        return sgs.stream().filter(sg -> activeAt(sg, d)).map(sg -> sg.getStudent().getId()).distinct().count();
    }

    // ── bucket lar ──────────────────────────────────────────────────────

    static List<LocalDate> buckets(LocalDate from, LocalDate to, GroupBy g) {
        List<LocalDate> out = new ArrayList<>();
        LocalDate d = from;
        while (!d.isAfter(to)) {
            out.add(d);
            d = switch (g) {
                case DAY -> d.plusDays(1);
                case WEEK -> d.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
                case MONTH -> d.withDayOfMonth(1).plusMonths(1);
            };
        }
        return out;
    }

    private static int bucketIndex(List<LocalDate> buckets, LocalDate d) {
        int i = buckets.size() - 1;
        while (i > 0 && buckets.get(i).isAfter(d)) {
            i--;
        }
        return i;
    }

    // ── yordamchilar ────────────────────────────────────────────────────

    private static Metric metric(BigDecimal value, BigDecimal previous, List<BigDecimal> series) {
        BigDecimal change = previous.signum() == 0 ? null
            : value.subtract(previous).multiply(BigDecimal.valueOf(100))
                .divide(previous.abs(), 1, RoundingMode.HALF_UP);
        return new Metric(value, previous, change, series);
    }

    private static void add(List<BigDecimal> series, int i, BigDecimal v) {
        series.set(i, series.get(i).add(v));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static final class Snapshot {
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expenses = BigDecimal.ZERO;
        BigDecimal payroll = BigDecimal.ZERO;
        BigDecimal examFees = BigDecimal.ZERO;
        BigDecimal newStudents = BigDecimal.ZERO;
        BigDecimal exits = BigDecimal.ZERO;
        BigDecimal activeAtEnd = BigDecimal.ZERO;
        BigDecimal leads = BigDecimal.ZERO;
        BigDecimal converted = BigDecimal.ZERO;
        final List<BigDecimal> incomeSeries;
        final List<BigDecimal> expenseSeries;
        final List<BigDecimal> payrollSeries;
        final List<BigDecimal> examFeeSeries;
        final List<BigDecimal> newStudentSeries;
        final List<BigDecimal> exitSeries;
        final List<BigDecimal> activeSeries;
        final List<BigDecimal> leadSeries;
        final List<BigDecimal> convertedSeries;
        final Map<String, BigDecimal> incomeByMethod = new TreeMap<>();
        final Map<String, Long> exitsByReason = new TreeMap<>();
        final List<LeadSource> bySource = new ArrayList<>();

        Snapshot(int n) {
            incomeSeries = zeros(n);
            expenseSeries = zeros(n);
            payrollSeries = zeros(n);
            examFeeSeries = zeros(n);
            newStudentSeries = zeros(n);
            exitSeries = zeros(n);
            activeSeries = zeros(n);
            leadSeries = zeros(n);
            convertedSeries = zeros(n);
        }

        BigDecimal net() {
            return income.add(examFees).subtract(expenses).subtract(payroll);
        }

        List<BigDecimal> netSeries() {
            List<BigDecimal> out = new ArrayList<>();
            for (int i = 0; i < incomeSeries.size(); i++) {
                out.add(incomeSeries.get(i).add(examFeeSeries.get(i)).subtract(expenseSeries.get(i))
                    .subtract(payrollSeries.get(i)));
            }
            return out;
        }

        private static List<BigDecimal> zeros(int n) {
            List<BigDecimal> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                out.add(BigDecimal.ZERO);
            }
            return out;
        }
    }
}
