package com.crm.service;

import com.crm.dto.response.TeacherKpiRankingItemDto;
import com.crm.dto.response.TeacherKpiRankingResponse;
import com.crm.dto.response.TeacherKpiScoresDto;
import com.crm.dto.response.TeacherKpiTrendPointDto;
import com.crm.entity.Teacher;
import com.crm.entity.enums.AttendanceStatus;
import com.crm.repository.AttendanceRepository;
import com.crm.repository.GroupRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.TeacherRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 4 mezonli o'qituvchi KPI (attendance / payment / on-time / retention).
 * Reyting uchun batch query — N+1 yo'q.
 */
@Service
@RequiredArgsConstructor
public class TeacherKpiService {

    private static final List<AttendanceStatus> PRESENT_STATUSES =
        List.of(AttendanceStatus.PRESENT, AttendanceStatus.LATE);
    private static final int DEFAULT_TREND_MONTHS = 6;
    private static final int MAX_TREND_MONTHS = 12;
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ISO_LOCAL_DATE;

    private final TeacherRepository teacherRepository;
    private final GroupRepository groupRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final AttendanceRepository attendanceRepository;

    @Transactional(readOnly = true)
    public TeacherKpiScoresDto computeScores(Long teacherId, LocalDate from, LocalDate to) {
        Map<Long, TeacherKpiScoresDto> all = computeScoresForTeachers(List.of(teacherId), from, to);
        return all.getOrDefault(teacherId, insufficient());
    }

    @Transactional(readOnly = true)
    public List<TeacherKpiTrendPointDto> buildTrend(
            Long teacherId, String period, LocalDate from, LocalDate to) {
        String p = normalizePeriod(period);
        List<TeacherKpiTrendPointDto> trend = new ArrayList<>();

        if ("daily".equals(p)) {
            LocalDate day = from;
            while (!day.isAfter(to)) {
                TeacherKpiScoresDto scores = computeScores(teacherId, day, day);
                trend.add(TeacherKpiTrendPointDto.builder()
                    .label(day.format(DAY_LABEL))
                    .overallScore(scores.getOverallScore())
                    .insufficientData(Boolean.TRUE.equals(scores.getInsufficientData()))
                    .build());
                day = day.plusDays(1);
            }
            return trend;
        }

        // monthly: from..to oraligidagi oylar (default oxirgi 6 oy)
        List<YearMonth> months = resolveTrendMonths(from, to);
        for (YearMonth ym : months) {
            LocalDate mFrom = ym.atDay(1);
            LocalDate mTo = ym.equals(YearMonth.from(to)) ? to : ym.atEndOfMonth();
            if (mTo.isBefore(mFrom)) {
                mTo = mFrom;
            }
            TeacherKpiScoresDto scores = computeScores(teacherId, mFrom, mTo);
            trend.add(TeacherKpiTrendPointDto.builder()
                .label(ym.format(MONTH_LABEL))
                .overallScore(scores.getOverallScore())
                .insufficientData(Boolean.TRUE.equals(scores.getInsufficientData()))
                .build());
        }
        return trend;
    }

    @Transactional(readOnly = true)
    public TeacherKpiRankingResponse getRanking(String period, LocalDate from, LocalDate to) {
        String p = normalizePeriod(period);
        List<Teacher> teachers = teacherRepository.findByIsActiveTrue();
        List<Long> teacherIds = teachers.stream().map(Teacher::getId).toList();

        Map<Long, TeacherKpiScoresDto> scores = computeScoresForTeachers(teacherIds, from, to);
        Map<Long, Integer> groupCounts = toIntMap(groupRepository.countGroupsGroupedByTeacher());
        Map<Long, long[]> paymentStats = toPaymentStatsMap(
            studentGroupRepository.countActivePaymentStatsGroupedByTeacher(LocalDate.now()));

        List<TeacherKpiRankingItemDto> items = new ArrayList<>();
        for (Teacher t : teachers) {
            TeacherKpiScoresDto s = scores.getOrDefault(t.getId(), insufficient());
            long[] pay = paymentStats.getOrDefault(t.getId(), new long[]{0, 0, 0, 0});
            items.add(TeacherKpiRankingItemDto.builder()
                .teacherId(t.getId())
                .teacherName(fullName(t))
                .photoUrl(t.getPhotoUrl())
                .groupCount(groupCounts.getOrDefault(t.getId(), 0))
                .studentCount((int) pay[0])
                .attendanceRate(s.getAttendanceRate())
                .paymentRate(s.getPaymentRate())
                .onTimePaymentRate(s.getOnTimePaymentRate())
                .retentionRate(s.getRetentionRate())
                .overallScore(s.getOverallScore())
                .insufficientData(Boolean.TRUE.equals(s.getInsufficientData()))
                .build());
        }

        items.sort(Comparator
            .comparing((TeacherKpiRankingItemDto i) -> Boolean.TRUE.equals(i.getInsufficientData()))
            .thenComparing(i -> i.getOverallScore() != null ? i.getOverallScore() : -1.0,
                Comparator.reverseOrder())
            .thenComparing(TeacherKpiRankingItemDto::getTeacherName,
                Comparator.nullsLast(String::compareToIgnoreCase)));

        int rank = 1;
        for (TeacherKpiRankingItemDto item : items) {
            item.setRank(rank++);
        }

        return TeacherKpiRankingResponse.builder()
            .period(p)
            .from(from)
            .to(to)
            .teachers(items)
            .build();
    }

    /**
     * Bir nechta o'qituvchi uchun bir xil davrdagi skorlar — batch query.
     */
    @Transactional(readOnly = true)
    public Map<Long, TeacherKpiScoresDto> computeScoresForTeachers(
            List<Long> teacherIds, LocalDate from, LocalDate to) {
        Map<Long, TeacherKpiScoresDto> result = new HashMap<>();
        if (teacherIds == null || teacherIds.isEmpty()) {
            return result;
        }

        Map<Long, long[]> attendance = toLongPairMap(
            attendanceRepository.countAttendanceStatsGroupedByTeacher(from, to, PRESENT_STATUSES));
        Map<Long, long[]> payment = toPaymentStatsMap(
            studentGroupRepository.countActivePaymentStatsGroupedByTeacher(LocalDate.now()));
        Map<Long, long[]> leaves = toLongPairMap(
            studentGroupRepository.countLeaveStatsGroupedByTeacher(from, to));

        for (Long teacherId : teacherIds) {
            long[] att = attendance.getOrDefault(teacherId, new long[]{0, 0});
            long present = att[0];
            long totalLessons = att[1];

            long[] pay = payment.getOrDefault(teacherId, new long[]{0, 0, 0, 0});
            long activeStudents = pay[0];
            long paidStudents = pay[1];
            long billableStudents = pay[2];
            long debtorStudents = pay[3];

            long[] leave = leaves.getOrDefault(teacherId, new long[]{0, 0});
            long graduated = leave[0];
            long left = leave[1];

            boolean noData = totalLessons == 0 && activeStudents == 0 && graduated == 0 && left == 0;
            if (noData) {
                result.put(teacherId, insufficient());
                continue;
            }

            Double attendanceRate = totalLessons > 0
                ? round1(present * 100.0 / totalLessons) : null;
            Double paymentRate = activeStudents > 0
                ? round1(paidStudents * 100.0 / activeStudents) : null;

            Double onTimePaymentRate = null;
            if (billableStudents > 0) {
                long onTime = Math.max(0, billableStudents - debtorStudents);
                onTimePaymentRate = round1(onTime * 100.0 / billableStudents);
            }

            long retained = activeStudents + graduated;
            long retentionDenom = retained + left;
            Double retentionRate = retentionDenom > 0
                ? round1(retained * 100.0 / retentionDenom) : null;

            List<Double> parts = new ArrayList<>();
            if (attendanceRate != null) parts.add(attendanceRate);
            if (paymentRate != null) parts.add(paymentRate);
            if (onTimePaymentRate != null) parts.add(onTimePaymentRate);
            if (retentionRate != null) parts.add(retentionRate);

            // Yetarli emas: hech qanday mezon hisoblanmasa
            if (parts.isEmpty()) {
                result.put(teacherId, insufficient());
                continue;
            }

            // Teng og'irlik: mavjud mezonlar o'rtachasi (4 ga bo'lish uchun null=0 emas —
            // faqat hisoblanganlari; lekin task (a+b+c+d)/4 deb aytadi.
            // Null mezonlar 0 o'rniga o'tkazib yuboriladi emas — agar kamida bitta
            // ma'lumot bo'lsa, mavjud 4 slot: yo'qlar uchun o'rtachaga faqat mavjudlar.
            // Task: overallScore = (a+b+c+d)/4. Null bo'lganini 0 deb emas.
            // Agar ba'zi mezonlar null bo'lsa, ularni o'rtachaga kiritmaymiz yoki?
            // "insufficient" faqat umuman ma'lumot yo'q. Partial data: mavjud / count.
            double overall = parts.stream().mapToDouble(Double::doubleValue).average().orElse(0);

            result.put(teacherId, TeacherKpiScoresDto.builder()
                .attendanceRate(attendanceRate)
                .paymentRate(paymentRate)
                .onTimePaymentRate(onTimePaymentRate)
                .retentionRate(retentionRate)
                .overallScore(round1(overall))
                .insufficientData(false)
                .build());
        }
        return result;
    }

    public static String normalizePeriod(String period) {
        if (period == null || period.isBlank()) {
            return "monthly";
        }
        String p = period.trim().toLowerCase();
        return "daily".equals(p) ? "daily" : "monthly";
    }

    public static LocalDate defaultFrom(String period, LocalDate from, LocalDate to) {
        if (from != null) {
            return from;
        }
        LocalDate end = to != null ? to : LocalDate.now();
        if ("daily".equals(normalizePeriod(period))) {
            return end.withDayOfMonth(1);
        }
        return end.withDayOfMonth(1);
    }

    public static LocalDate defaultTo(LocalDate to) {
        return to != null ? to : LocalDate.now();
    }

    private List<YearMonth> resolveTrendMonths(LocalDate from, LocalDate to) {
        YearMonth end = YearMonth.from(to);
        YearMonth start = YearMonth.from(from);
        long monthsBetween = start.until(end, java.time.temporal.ChronoUnit.MONTHS) + 1;
        if (monthsBetween < 2) {
            start = end.minusMonths(DEFAULT_TREND_MONTHS - 1L);
        } else if (monthsBetween > MAX_TREND_MONTHS) {
            start = end.minusMonths(MAX_TREND_MONTHS - 1L);
        }
        List<YearMonth> list = new ArrayList<>();
        YearMonth cur = start;
        while (!cur.isAfter(end)) {
            list.add(cur);
            cur = cur.plusMonths(1);
        }
        return list;
    }

    private static TeacherKpiScoresDto insufficient() {
        return TeacherKpiScoresDto.builder()
            .attendanceRate(null)
            .paymentRate(null)
            .onTimePaymentRate(null)
            .retentionRate(null)
            .overallScore(null)
            .insufficientData(true)
            .build();
    }

    private static String fullName(Teacher t) {
        return ((t.getFirstName() != null ? t.getFirstName() : "")
            + " "
            + (t.getLastName() != null ? t.getLastName() : "")).trim();
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static Map<Long, Integer> toIntMap(List<Object[]> rows) {
        Map<Long, Integer> map = new HashMap<>();
        for (Object[] row : rows) {
            if (row[0] == null) continue;
            map.put(((Number) row[0]).longValue(), ((Number) row[1]).intValue());
        }
        return map;
    }

    /** [present, total] or [graduated, left] */
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

    /** [active, paid, billable, debtor] */
    private static Map<Long, long[]> toPaymentStatsMap(List<Object[]> rows) {
        Map<Long, long[]> map = new HashMap<>();
        for (Object[] row : rows) {
            if (row[0] == null) continue;
            long active = row[1] != null ? ((Number) row[1]).longValue() : 0L;
            long paid = row[2] != null ? ((Number) row[2]).longValue() : 0L;
            long billable = row[3] != null ? ((Number) row[3]).longValue() : 0L;
            long debtor = row[4] != null ? ((Number) row[4]).longValue() : 0L;
            map.put(((Number) row[0]).longValue(), new long[]{active, paid, billable, debtor});
        }
        return map;
    }
}
