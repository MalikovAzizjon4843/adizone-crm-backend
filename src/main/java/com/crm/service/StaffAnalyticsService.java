package com.crm.service;

import com.crm.dto.response.StaffAnalyticsResponse;
import com.crm.dto.response.StaffMemberMetricsDto;
import com.crm.dto.response.StaffSummaryResponse;
import com.crm.dto.response.StaffTrendResponse;
import com.crm.dto.response.TeacherKpiScoresDto;
import com.crm.entity.Teacher;
import com.crm.entity.User;
import com.crm.entity.enums.UserRole;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.AttendanceRepository;
import com.crm.repository.AttendanceUnlockRequestRepository;
import com.crm.repository.GroupRepository;
import com.crm.repository.LeadRepository;
import com.crm.repository.PaymentRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.TeacherRepository;
import com.crm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StaffAnalyticsService {

    private static final Set<UserRole> STAFF_ROLES = EnumSet.of(
        UserRole.SUPER_ADMIN, UserRole.ADMIN, UserRole.ADMINISTRATOR,
        UserRole.SALES_MANAGER, UserRole.TEACHER, UserRole.ACCOUNTANT);
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final int DEFAULT_TREND_MONTHS = 6;
    private static final int MAX_TREND_MONTHS = 12;

    private final UserRepository userRepository;
    private final LeadRepository leadRepository;
    private final PaymentRepository paymentRepository;
    private final AttendanceRepository attendanceRepository;
    private final AttendanceUnlockRequestRepository unlockRequestRepository;
    private final TeacherRepository teacherRepository;
    private final GroupRepository groupRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final TeacherKpiService teacherKpiService;

    @Transactional(readOnly = true)
    public StaffAnalyticsResponse getStaffAnalytics(String period, LocalDate from, LocalDate to) {
        String p = TeacherKpiService.normalizePeriod(period);
        LocalDate rangeTo = TeacherKpiService.defaultTo(to);
        LocalDate rangeFrom = TeacherKpiService.defaultFrom(p, from, rangeTo);
        LocalDateTime fromDt = rangeFrom.atStartOfDay();
        LocalDateTime toExclusive = rangeTo.plusDays(1).atStartOfDay();

        List<User> users = userRepository.findByIsActiveTrue().stream()
            .filter(u -> u.getRole() != null && STAFF_ROLES.contains(u.getRole()))
            .toList();

        Map<Long, long[]> leadStats = toLongPairMap(
            leadRepository.countAssignedAndConvertedGroupedByUser(fromDt, toExclusive));
        Map<Long, PaymentAgg> payStats = toPaymentMap(
            paymentRepository.sumReceivedGroupedByUser(rangeFrom, rangeTo));
        Map<Long, Long> markedStats = toLongMap(
            attendanceRepository.countMarkedGroupedByUser(rangeFrom, rangeTo));
        Map<Long, Long> unlockStats = toLongMap(
            unlockRequestRepository.countGroupedByTeacherUser(fromDt, toExclusive));

        Map<Long, Teacher> teacherByUserId = teacherRepository.findByIsActiveTrue().stream()
            .filter(t -> t.getUser() != null && t.getUser().getId() != null)
            .collect(Collectors.toMap(t -> t.getUser().getId(), t -> t, (a, b) -> a));

        List<Long> teacherIds = teacherByUserId.values().stream().map(Teacher::getId).toList();
        Map<Long, TeacherKpiScoresDto> kpiByTeacher = teacherKpiService
            .computeScoresForTeachers(teacherIds, rangeFrom, rangeTo);
        Map<Long, Integer> groupCounts = toIntMap(groupRepository.countGroupsGroupedByTeacher());
        Map<Long, long[]> paymentStudentStats = toPaymentStudentMap(
            studentGroupRepository.countActivePaymentStatsGroupedByTeacher(LocalDate.now()));

        List<StaffMemberMetricsDto> staff = new ArrayList<>();
        for (User user : users) {
            staff.add(buildMember(
                user,
                leadStats, payStats, markedStats, unlockStats,
                teacherByUserId, kpiByTeacher, groupCounts, paymentStudentStats));
        }

        staff.sort(Comparator
            .comparing((StaffMemberMetricsDto m) -> Boolean.TRUE.equals(m.getInsufficientData()))
            .thenComparing(m -> m.getOverallScore() != null ? m.getOverallScore() : -1.0,
                Comparator.reverseOrder())
            .thenComparing(m -> m.getPaymentsAmount() != null ? m.getPaymentsAmount() : BigDecimal.valueOf(-1),
                Comparator.reverseOrder())
            .thenComparing(StaffMemberMetricsDto::getFullName,
                Comparator.nullsLast(String::compareToIgnoreCase)));

        return StaffAnalyticsResponse.builder()
            .period(p)
            .from(rangeFrom)
            .to(rangeTo)
            .staff(staff)
            .build();
    }

    @Transactional(readOnly = true)
    public StaffTrendResponse getStaffTrend(
            Long userId, String period, Integer count, LocalDate from, LocalDate to) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        String p = TeacherKpiService.normalizePeriod(period);

        Teacher teacher = teacherRepository.findByUser_Id(userId).orElse(null);
        Long teacherId = teacher != null ? teacher.getId() : null;

        List<StaffTrendResponse.StaffTrendPointDto> points = new ArrayList<>();

        if ("daily".equals(p)) {
            LocalDate rangeTo = TeacherKpiService.defaultTo(to);
            LocalDate rangeFrom = from != null ? from : rangeTo.withDayOfMonth(1);
            for (LocalDate day = rangeFrom; !day.isAfter(rangeTo); day = day.plusDays(1)) {
                points.add(buildTrendPoint(userId, teacherId, day.format(DAY_LABEL), day, day));
            }
        } else {
            LocalDate rangeTo = TeacherKpiService.defaultTo(to);
            int months = count != null && count > 0
                ? Math.min(count, MAX_TREND_MONTHS) : DEFAULT_TREND_MONTHS;
            YearMonth end = YearMonth.from(rangeTo);
            YearMonth start = from != null
                ? YearMonth.from(from)
                : end.minusMonths(months - 1L);
            if (start.until(end, java.time.temporal.ChronoUnit.MONTHS) + 1 > MAX_TREND_MONTHS) {
                start = end.minusMonths(MAX_TREND_MONTHS - 1L);
            }
            for (YearMonth ym = start; !ym.isAfter(end); ym = ym.plusMonths(1)) {
                LocalDate mFrom = ym.atDay(1);
                LocalDate mTo = ym.equals(YearMonth.from(rangeTo)) ? rangeTo : ym.atEndOfMonth();
                points.add(buildTrendPoint(userId, teacherId, ym.format(MONTH_LABEL), mFrom, mTo));
            }
        }

        return StaffTrendResponse.builder()
            .userId(user.getId())
            .period(p)
            .points(points)
            .build();
    }

    @Transactional(readOnly = true)
    public StaffSummaryResponse getStaffSummary(String period, LocalDate from, LocalDate to) {
        StaffAnalyticsResponse full = getStaffAnalytics(period, from, to);
        List<StaffMemberMetricsDto> staff = full.getStaff();

        Map<String, Long> byRole = new LinkedHashMap<>();
        for (StaffMemberMetricsDto m : staff) {
            String role = m.getRole() != null ? m.getRole() : "UNKNOWN";
            byRole.merge(role, 1L, Long::sum);
        }

        List<StaffSummaryResponse.TopConversionItem> topConv = staff.stream()
            .filter(m -> !Boolean.TRUE.equals(m.getInsufficientData()))
            .filter(m -> m.getConversionRate() != null)
            .sorted(Comparator.comparing(StaffMemberMetricsDto::getConversionRate).reversed())
            .limit(5)
            .map(m -> StaffSummaryResponse.TopConversionItem.builder()
                .userId(m.getUserId())
                .fullName(m.getFullName())
                .conversionRate(m.getConversionRate())
                .build())
            .toList();

        List<StaffSummaryResponse.TopPaymentItem> topPay = staff.stream()
            .filter(m -> !Boolean.TRUE.equals(m.getInsufficientData()))
            .filter(m -> m.getPaymentsAmount() != null
                && m.getPaymentsAmount().compareTo(BigDecimal.ZERO) > 0)
            .sorted(Comparator.comparing(StaffMemberMetricsDto::getPaymentsAmount).reversed())
            .limit(5)
            .map(m -> StaffSummaryResponse.TopPaymentItem.builder()
                .userId(m.getUserId())
                .fullName(m.getFullName())
                .paymentsAmount(m.getPaymentsAmount())
                .build())
            .toList();

        List<StaffSummaryResponse.TopKpiItem> topKpi = staff.stream()
            .filter(m -> UserRole.TEACHER.name().equals(m.getRole()))
            .filter(m -> !Boolean.TRUE.equals(m.getInsufficientData()))
            .filter(m -> m.getOverallScore() != null)
            .sorted(Comparator.comparing(StaffMemberMetricsDto::getOverallScore).reversed())
            .limit(5)
            .map(m -> StaffSummaryResponse.TopKpiItem.builder()
                .userId(m.getUserId())
                .fullName(m.getFullName())
                .overallScore(m.getOverallScore())
                .build())
            .toList();

        return StaffSummaryResponse.builder()
            .period(full.getPeriod())
            .from(full.getFrom())
            .to(full.getTo())
            .totalStaff(staff.size())
            .byRole(byRole)
            .topByConversion(topConv)
            .topByPayments(topPay)
            .topByKpi(topKpi)
            .build();
    }

    private StaffTrendResponse.StaffTrendPointDto buildTrendPoint(
            Long userId, Long teacherId, String label, LocalDate from, LocalDate to) {
        LocalDateTime fromDt = from.atStartOfDay();
        LocalDateTime toEx = to.plusDays(1).atStartOfDay();
        long converted = leadRepository.countConvertedByUserInRange(userId, fromDt, toEx);
        List<Object[]> payRows = paymentRepository.sumReceivedByUserInRange(userId, from, to);
        BigDecimal amount = BigDecimal.ZERO;
        if (payRows != null && !payRows.isEmpty() && payRows.get(0) != null) {
            Object[] pay = payRows.get(0);
            if (pay.length > 1 && pay[1] != null) {
                amount = toBigDecimal(pay[1]);
            }
        }

        Double overall = null;
        boolean insufficient = converted == 0 && amount.compareTo(BigDecimal.ZERO) == 0;
        if (teacherId != null) {
            TeacherKpiScoresDto kpi = teacherKpiService.computeScores(teacherId, from, to);
            overall = kpi.getOverallScore();
            if (!Boolean.TRUE.equals(kpi.getInsufficientData())) {
                insufficient = false;
            }
        }

        return StaffTrendResponse.StaffTrendPointDto.builder()
            .label(label)
            .leadsConverted(insufficient && converted == 0 ? null : converted)
            .paymentsAmount(insufficient && amount.compareTo(BigDecimal.ZERO) == 0 ? null : amount)
            .overallScore(overall)
            .insufficientData(insufficient && overall == null)
            .build();
    }

    private StaffMemberMetricsDto buildMember(
            User user,
            Map<Long, long[]> leadStats,
            Map<Long, PaymentAgg> payStats,
            Map<Long, Long> markedStats,
            Map<Long, Long> unlockStats,
            Map<Long, Teacher> teacherByUserId,
            Map<Long, TeacherKpiScoresDto> kpiByTeacher,
            Map<Long, Integer> groupCounts,
            Map<Long, long[]> paymentStudentStats) {

        long[] leads = leadStats.getOrDefault(user.getId(), new long[]{0, 0});
        long assigned = leads[0];
        long converted = leads[1];
        PaymentAgg pay = payStats.getOrDefault(user.getId(), PaymentAgg.ZERO);
        long marked = markedStats.getOrDefault(user.getId(), 0L);
        long unlocks = unlockStats.getOrDefault(user.getId(), 0L);

        boolean isTeacher = user.getRole() == UserRole.TEACHER;
        Teacher teacher = teacherByUserId.get(user.getId());
        TeacherKpiScoresDto kpi = null;
        Integer groupCount = null;
        Integer studentCount = null;

        if (isTeacher && teacher != null) {
            kpi = kpiByTeacher.getOrDefault(teacher.getId(),
                TeacherKpiScoresDto.builder().insufficientData(true).build());
            groupCount = groupCounts.getOrDefault(teacher.getId(), 0);
            long[] st = paymentStudentStats.getOrDefault(teacher.getId(), new long[]{0, 0, 0, 0});
            studentCount = (int) st[0];
        }

        boolean hasActivity = assigned > 0 || converted > 0
            || pay.count > 0 || marked > 0 || unlocks > 0
            || (kpi != null && !Boolean.TRUE.equals(kpi.getInsufficientData()));

        if (!hasActivity) {
            return StaffMemberMetricsDto.builder()
                .userId(user.getId())
                .fullName(fullName(user))
                .role(user.getRole() != null ? user.getRole().name() : null)
                .photoUrl(user.getPhotoUrl())
                .leadsAssigned(null)
                .leadsConverted(null)
                .conversionRate(null)
                .paymentsReceived(null)
                .paymentsAmount(null)
                .studentsCreated(null)
                .groupCount(isTeacher ? groupCount : null)
                .studentCount(isTeacher ? studentCount : null)
                .attendanceRate(null)
                .paymentRate(null)
                .onTimePaymentRate(null)
                .retentionRate(null)
                .overallScore(null)
                .attendanceMarkedCount(null)
                .unlockRequestCount(null)
                .insufficientData(true)
                .activityLabel("Faoliyat yo'q")
                .build();
        }

        Double conversionRate = null;
        if (assigned > 0) {
            conversionRate = BigDecimal.valueOf(converted * 100.0 / assigned)
                .setScale(1, RoundingMode.HALF_UP).doubleValue();
        }

        StaffMemberMetricsDto.StaffMemberMetricsDtoBuilder b = StaffMemberMetricsDto.builder()
            .userId(user.getId())
            .fullName(fullName(user))
            .role(user.getRole() != null ? user.getRole().name() : null)
            .photoUrl(user.getPhotoUrl())
            .leadsAssigned(assigned)
            .leadsConverted(converted)
            .conversionRate(conversionRate)
            .paymentsReceived(pay.count)
            .paymentsAmount(pay.amount)
            .studentsCreated(null)
            .insufficientData(false)
            .activityLabel(null);

        if (isTeacher) {
            b.groupCount(groupCount)
                .studentCount(studentCount)
                .attendanceMarkedCount(marked)
                .unlockRequestCount(unlocks);
            if (kpi != null) {
                b.attendanceRate(kpi.getAttendanceRate())
                    .paymentRate(kpi.getPaymentRate())
                    .onTimePaymentRate(kpi.getOnTimePaymentRate())
                    .retentionRate(kpi.getRetentionRate())
                    .overallScore(kpi.getOverallScore());
            }
        }

        return b.build();
    }

    private static String fullName(User u) {
        return ((u.getFirstName() != null ? u.getFirstName() : "")
            + " "
            + (u.getLastName() != null ? u.getLastName() : "")).trim();
    }

    private static Map<Long, long[]> toLongPairMap(List<Object[]> rows) {
        Map<Long, long[]> map = new HashMap<>();
        for (Object[] row : rows) {
            if (row[0] == null) continue;
            long a = row[1] != null ? ((Number) row[1]).longValue() : 0L;
            long b = row[2] != null ? ((Number) row[2]).longValue() : 0L;
            map.put(((Number) row[0]).longValue(), new long[]{a, b});
        }
        return map;
    }

    private static Map<Long, Long> toLongMap(List<Object[]> rows) {
        Map<Long, Long> map = new HashMap<>();
        for (Object[] row : rows) {
            if (row[0] == null) continue;
            map.put(((Number) row[0]).longValue(),
                row[1] != null ? ((Number) row[1]).longValue() : 0L);
        }
        return map;
    }

    private static Map<Long, Integer> toIntMap(List<Object[]> rows) {
        Map<Long, Integer> map = new HashMap<>();
        for (Object[] row : rows) {
            if (row[0] == null) continue;
            map.put(((Number) row[0]).longValue(),
                row[1] != null ? ((Number) row[1]).intValue() : 0);
        }
        return map;
    }

    private static Map<Long, PaymentAgg> toPaymentMap(List<Object[]> rows) {
        Map<Long, PaymentAgg> map = new HashMap<>();
        for (Object[] row : rows) {
            if (row[0] == null) continue;
            long count = row[1] != null ? ((Number) row[1]).longValue() : 0L;
            BigDecimal amount = row[2] != null ? toBigDecimal(row[2]) : BigDecimal.ZERO;
            map.put(((Number) row[0]).longValue(), new PaymentAgg(count, amount));
        }
        return map;
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        return new BigDecimal(value.toString());
    }

    private static Map<Long, long[]> toPaymentStudentMap(List<Object[]> rows) {
        Map<Long, long[]> map = new HashMap<>();
        for (Object[] row : rows) {
            if (row[0] == null) continue;
            long[] vals = new long[row.length - 1];
            for (int i = 1; i < row.length; i++) {
                vals[i - 1] = row[i] != null ? ((Number) row[i]).longValue() : 0L;
            }
            map.put(((Number) row[0]).longValue(), vals);
        }
        return map;
    }

    private record PaymentAgg(long count, BigDecimal amount) {
        static final PaymentAgg ZERO = new PaymentAgg(0, BigDecimal.ZERO);
    }
}
