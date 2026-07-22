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
import java.time.format.DateTimeFormatter;
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
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter YEAR_LABEL = DateTimeFormatter.ofPattern("yyyy");

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
        return getRevenueAnalytics("monthly", months);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getRevenueAnalytics(String period, Integer count) {
        String normalized = normalizePeriod(period);
        int safeCount = normalizeCount(normalized, count);
        LocalDate today = LocalDate.now();
        LocalDate from = switch (normalized) {
            case "daily" -> today.minusDays(safeCount - 1L);
            case "yearly" -> today.minusYears(safeCount - 1L).withDayOfYear(1);
            default -> today.minusMonths(safeCount - 1L).withDayOfMonth(1);
        };
        LocalDate to = today;

        Map<LocalDate, BigDecimal> amounts = loadRevenueBuckets(normalized, from, to);
        List<Map<String, Object>> points = new ArrayList<>();

        switch (normalized) {
            case "daily" -> {
                LocalDate cursor = from;
                while (!cursor.isAfter(to)) {
                    points.add(point(cursor.format(DateTimeFormatter.ISO_LOCAL_DATE),
                        amounts.getOrDefault(cursor, BigDecimal.ZERO)));
                    cursor = cursor.plusDays(1);
                }
            }
            case "yearly" -> {
                LocalDate cursor = from.withDayOfYear(1);
                LocalDate end = to.withDayOfYear(1);
                while (!cursor.isAfter(end)) {
                    points.add(point(cursor.format(YEAR_LABEL),
                        amounts.getOrDefault(cursor, BigDecimal.ZERO)));
                    cursor = cursor.plusYears(1);
                }
            }
            default -> {
                YearMonth cursor = YearMonth.from(from);
                YearMonth end = YearMonth.from(to);
                while (!cursor.isAfter(end)) {
                    LocalDate bucket = cursor.atDay(1);
                    points.add(point(bucket.format(MONTH_LABEL),
                        amounts.getOrDefault(bucket, BigDecimal.ZERO)));
                    cursor = cursor.plusMonths(1);
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", normalized);
        result.put("points", points);
        return result;
    }

    private Map<LocalDate, BigDecimal> loadRevenueBuckets(String period, LocalDate from, LocalDate to) {
        List<Object[]> rows = switch (period) {
            case "daily" -> paymentRepository.getDailyRevenueBuckets(from, to);
            case "yearly" -> paymentRepository.getYearlyRevenueBuckets(from, to);
            default -> paymentRepository.getMonthlyRevenueBuckets(from, to);
        };
        Map<LocalDate, BigDecimal> amounts = new HashMap<>();
        for (Object[] row : rows) {
            LocalDate bucket = toLocalDate(row[0]);
            BigDecimal amount = row[1] instanceof BigDecimal bd
                ? bd
                : BigDecimal.valueOf(((Number) row[1]).doubleValue());
            amounts.put(bucket, amount);
        }
        return amounts;
    }

    private static LocalDate toLocalDate(Object value) {
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime().toLocalDate();
        }
        if (value instanceof LocalDate date) {
            return date;
        }
        return LocalDate.parse(String.valueOf(value).substring(0, 10));
    }

    private static Map<String, Object> point(String label, BigDecimal amount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", label);
        row.put("amount", amount);
        return row;
    }

    private static String normalizePeriod(String period) {
        if (period == null || period.isBlank()) {
            return "monthly";
        }
        String normalized = period.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "daily", "monthly", "yearly" -> normalized;
            default -> "monthly";
        };
    }

    private static int normalizeCount(String period, Integer count) {
        int fallback = switch (period) {
            case "daily" -> 30;
            case "yearly" -> 3;
            default -> 6;
        };
        int max = switch (period) {
            case "daily" -> 365;
            case "yearly" -> 10;
            default -> 36;
        };
        int value = count == null ? fallback : count;
        return Math.min(Math.max(value, 1), max);
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
