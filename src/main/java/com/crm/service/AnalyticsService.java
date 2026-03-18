package com.crm.service;

import com.crm.dto.response.DashboardResponse;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.StudentStatus;
import com.crm.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

    @Transactional(readOnly = true)
    public DashboardResponse getDashboard() {
        LocalDate now = LocalDate.now();
        LocalDate monthStart = now.withDayOfMonth(1);
        LocalDateTime monthStartDT = monthStart.atStartOfDay();
        LocalDateTime monthEndDT = now.atTime(23, 59, 59);

        // Student stats
        long totalStudents = studentRepository.count();
        long activeStudents = studentRepository.countByStatus(StudentStatus.ACTIVE);
        long frozenStudents = studentRepository.countByStatus(StudentStatus.FROZEN);

        // Group stats
        long totalGroups = groupRepository.count();
        long activeGroups = groupRepository.countByStatus(GroupStatus.ACTIVE);

        // Teacher stats
        long totalTeachers = teacherRepository.findByIsActiveTrue().size();

        // Debtor count
        long debtorCount = studentGroupRepository.findDebtors(now).size();

        // Finance
        BigDecimal monthlyRevenue = Optional.ofNullable(
            paymentRepository.sumAmountByDateRange(monthStartDT, monthEndDT)
        ).orElse(BigDecimal.ZERO);

        BigDecimal monthlyExpenses = Optional.ofNullable(
            expenseRepository.sumByDateRange(monthStart, now)
        ).orElse(BigDecimal.ZERO);

        // Attendance rate this month
        long totalAttendance = attendanceRepository.countTotalByDateRange(monthStart, now);
        long presentAttendance = attendanceRepository.countPresentByDateRange(monthStart, now);
        double attendanceRate = totalAttendance > 0
            ? Math.round((double) presentAttendance / totalAttendance * 1000.0) / 10.0 : 0.0;

        // Students by marketing source
        Map<String, Long> studentsBySource = new LinkedHashMap<>();
        studentRepository.countByMarketingSourceGrouped()
            .forEach(row -> studentsBySource.put(row[0].toString(), (Long) row[1]));

        // Revenue chart (last 6 months)
        List<Map<String, Object>> revenueChart = new ArrayList<>();
        paymentRepository.getMonthlyRevenue(now.minusMonths(6).atStartOfDay())
            .forEach(row -> {
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("month", row[0]);
                point.put("year", row[1]);
                point.put("revenue", row[2]);
                revenueChart.add(point);
            });

        // Student growth chart (last 6 months)
        List<Map<String, Object>> growthChart = new ArrayList<>();
        studentRepository.getStudentGrowthByMonth(now.minusMonths(6).atStartOfDay())
            .forEach(row -> {
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("month", row[0]);
                point.put("year", row[1]);
                point.put("count", row[2]);
                growthChart.add(point);
            });

        return DashboardResponse.builder()
            .totalStudents(totalStudents)
            .activeStudents(activeStudents)
            .frozenStudents(frozenStudents)
            .totalGroups(totalGroups)
            .activeGroups(activeGroups)
            .totalTeachers(totalTeachers)
            .debtorCount(debtorCount)
            .monthlyRevenue(monthlyRevenue)
            .monthlyExpenses(monthlyExpenses)
            .netProfit(monthlyRevenue.subtract(monthlyExpenses))
            .attendanceRate(attendanceRate)
            .studentsBySource(studentsBySource)
            .revenueChart(revenueChart)
            .studentGrowthChart(growthChart)
            .build();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getRevenueAnalytics(int months) {
        LocalDateTime from = LocalDate.now().minusMonths(months).atStartOfDay();
        List<Map<String, Object>> monthly = new ArrayList<>();
        paymentRepository.getMonthlyRevenue(from).forEach(row -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("month", row[0]);
            m.put("year", row[1]);
            m.put("revenue", row[2]);
            monthly.add(m);
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("monthly", monthly);
        result.put("totalRevenue", monthly.stream()
            .mapToDouble(m -> ((Number) m.get("revenue")).doubleValue()).sum());
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
