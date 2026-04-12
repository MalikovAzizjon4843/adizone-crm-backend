package com.crm.service;

import com.crm.dto.response.*;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.StudentStatus;
import com.crm.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crm.entity.enums.MarketingSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final StudentRepository studentRepository;
    private final GroupRepository groupRepository;
    private final TeacherRepository teacherRepository;
    private final PaymentRepository paymentRepository;
    private final ExpenseRepository expenseRepository;
    private final AttendanceRepository attendanceRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final ParentRepository parentRepository;
    private final LeaveRepository leaveRepository;
    private final PayrollRepository payrollRepository;
    private final NoticeRepository noticeRepository;
    private final CourseRepository courseRepository;

    @Transactional(readOnly = true)
    public DashboardResponse getDashboard() {
        LocalDate now = LocalDate.now();
        LocalDate monthStart = now.withDayOfMonth(1);
        LocalDate monthEnd = now;

        long totalStudents = studentRepository.count();
        long activeStudents = studentRepository.countByStatus(StudentStatus.ACTIVE);

        long totalGroups = groupRepository.count();
        long activeGroups = groupRepository.countByStatus(GroupStatus.ACTIVE);

        long totalTeachers = teacherRepository.count();
        long activeTeachers = teacherRepository.countByIsActiveTrue();

        long totalCourses = courseRepository.count();

        long debtorCount = studentGroupRepository.findDebtors(now).size();

        BigDecimal monthlyRevenue = Optional.ofNullable(
            paymentRepository.sumAmountByDateRange(monthStart, monthEnd)
        ).orElse(BigDecimal.ZERO);

        BigDecimal monthlyExpenses = Optional.ofNullable(
            expenseRepository.sumByDateRange(monthStart, now)
        ).orElse(BigDecimal.ZERO);

        long totalAttendance = attendanceRepository.countTotalByDateRange(monthStart, now);
        long presentAttendance = attendanceRepository.countPresentByDateRange(monthStart, now);
        double attendanceRate = totalAttendance > 0
            ? Math.round((double) presentAttendance / totalAttendance * 1000.0) / 10.0 : 0.0;

        Map<String, Long> studentsBySource = new LinkedHashMap<>();
        studentRepository.countByMarketingSourceGrouped()
            .forEach(row -> studentsBySource.put(row[0].toString(), (Long) row[1]));

        LocalDate chartFrom = now.minusMonths(6).withDayOfMonth(1);
        List<Map<String, Object>> revenueChart = new ArrayList<>();
        paymentRepository.getMonthlyRevenue(chartFrom).forEach(row -> {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("month", ((Number) row[0]).intValue());
            point.put("year", ((Number) row[1]).intValue());
            point.put("revenue", row[2]);
            revenueChart.add(point);
        });

        List<Map<String, Object>> growthChart = new ArrayList<>();
        studentRepository.getStudentGrowthByMonth(now.minusMonths(6).atStartOfDay())
            .forEach(row -> {
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("month", row[0]);
                point.put("year", row[1]);
                point.put("count", row[2]);
                growthChart.add(point);
            });

        long totalParents = parentRepository.count();
        long pendingLeaves = leaveRepository.countPending();
        long unpaidPayroll = payrollRepository.countPending();

        List<NoticeResponse> latestNotices = noticeRepository
            .findTop5ByIsPublishedTrueOrderByPublishedAtDesc().stream()
            .map(n -> NoticeResponse.builder()
                .id(n.getId()).uuid(n.getUuid())
                .title(n.getTitle()).content(n.getContent())
                .noticeDate(n.getNoticeDate())
                .publishedTo(n.getPublishedTo())
                .noticeType(n.getNoticeType()).isPublished(n.getIsPublished())
                .publishedAt(n.getPublishedAt()).createdAt(n.getCreatedAt()).build())
            .collect(Collectors.toList());

        List<PaymentResponse> recentPayments = paymentRepository
            .findTop10ByOrderByPaymentDateDesc().stream()
            .map(p -> PaymentResponse.builder()
                .id(p.getId()).uuid(p.getUuid())
                .receiptNumber(p.getReceiptNumber())
                .formattedAmount(formatUzs(p.getAmount()))
                .amount(p.getAmount()).paymentDate(p.getPaymentDate())
                .paymentMethod(p.getPaymentMethod()).status(p.getStatus()).build())
            .collect(Collectors.toList());

        return DashboardResponse.builder()
            .totalStudents(totalStudents)
            .activeStudents(activeStudents)
            .totalTeachers(totalTeachers)
            .activeTeachers(activeTeachers)
            .totalGroups(totalGroups)
            .activeGroups(activeGroups)
            .totalCourses(totalCourses)
            .debtorCount(debtorCount)
            .monthlyRevenue(monthlyRevenue)
            .monthlyExpenses(monthlyExpenses)
            .netProfit(monthlyRevenue.subtract(monthlyExpenses))
            .attendanceRate(attendanceRate)
            .totalParents(totalParents)
            .pendingLeaves(pendingLeaves)
            .unpaidPayroll(unpaidPayroll)
            .studentsBySource(studentsBySource)
            .revenueChart(revenueChart)
            .studentGrowthChart(growthChart)
            .latestNotices(latestNotices)
            .recentPayments(recentPayments)
            .build();
    }

    private static String formatUzs(BigDecimal amount) {
        if (amount == null) {
            return "0 so'm";
        }
        long v = amount.setScale(0, java.math.RoundingMode.HALF_UP).longValue();
        return String.format(Locale.US, "%,d", v).replace(',', ' ') + " so'm";
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getRevenueAnalytics(int months) {
        months = Math.min(Math.max(months, 1), 36);
        LocalDate now = LocalDate.now();
        LocalDate from = now.minusMonths(months - 1L).withDayOfMonth(1);

        Map<String, BigDecimal> revenueByKey = new HashMap<>();
        paymentRepository.getMonthlyRevenue(from).forEach(row -> {
            int m = ((Number) row[0]).intValue();
            int y = ((Number) row[1]).intValue();
            BigDecimal sum = (BigDecimal) row[2];
            revenueByKey.merge(key(y, m), sum, BigDecimal::add);
        });

        Map<String, BigDecimal> expenseByKey = new HashMap<>();
        expenseRepository.getMonthlyExpenses(from).forEach(row -> {
            int m = ((Number) row[0]).intValue();
            int y = ((Number) row[1]).intValue();
            BigDecimal sum = (BigDecimal) row[2];
            expenseByKey.merge(key(y, m), sum, BigDecimal::add);
        });

        List<Map<String, Object>> series = new ArrayList<>();
        YearMonth cursor = YearMonth.from(from);
        YearMonth end = YearMonth.from(now);
        while (!cursor.isAfter(end)) {
            int y = cursor.getYear();
            int m = cursor.getMonthValue();
            String k = key(y, m);
            BigDecimal rev = revenueByKey.getOrDefault(k, BigDecimal.ZERO);
            BigDecimal exp = expenseByKey.getOrDefault(k, BigDecimal.ZERO);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("month", m);
            row.put("year", y);
            row.put("revenue", rev);
            row.put("expenses", exp);
            row.put("profit", rev.subtract(exp));
            series.add(row);
            cursor = cursor.plusMonths(1);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("monthly", series);
        result.put("totalRevenue", series.stream()
            .map(r -> (BigDecimal) r.get("revenue"))
            .reduce(BigDecimal.ZERO, BigDecimal::add));
        return result;
    }

    private static String key(int year, int month) {
        return year + "-" + month;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getMarketingSources() {
        Map<String, Long> bySource = new LinkedHashMap<>();
        for (MarketingSource source : MarketingSource.values()) {
            bySource.put(source.name(), studentRepository.countByMarketingSource(source));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bySource", bySource);
        result.put("total", bySource.values().stream().mapToLong(Long::longValue).sum());
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getStudentAnalytics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", studentRepository.count());
        result.put("active", studentRepository.countByStatus(StudentStatus.ACTIVE));
        result.put("frozen", studentRepository.countByStatus(StudentStatus.FROZEN));
        result.put("finished", studentRepository.countByStatus(StudentStatus.FINISHED));
        result.put("left", studentRepository.countByStatus(StudentStatus.LEFT));

        Map<String, Long> bySource = new LinkedHashMap<>();
        studentRepository.countByMarketingSourceGrouped()
            .forEach(row -> bySource.put(row[0].toString(), (Long) row[1]));
        result.put("byMarketingSource", bySource);

        result.put("debtors", studentGroupRepository.findDebtors(LocalDate.now()).size());
        return result;
    }
}
