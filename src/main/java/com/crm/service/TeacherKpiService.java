package com.crm.service;

import com.crm.dto.response.TeacherKpiRankingItemDto;
import com.crm.dto.response.TeacherKpiRankingResponse;
import com.crm.dto.response.TeacherKpiScoresDto;
import com.crm.dto.response.TeacherKpiTrendPointDto;
import com.crm.entity.Teacher;
import com.crm.entity.TeacherKpiMonthly;
import com.crm.entity.enums.AttendanceStatus;
import com.crm.entity.enums.BillingPeriodStatus;
import com.crm.exception.BadRequestException;
import com.crm.repository.AttendanceRepository;
import com.crm.repository.BillingPeriodRepository;
import com.crm.repository.GroupRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.TeacherKpiMonthlyRepository;
import com.crm.repository.TeacherRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 4 mezonli o'qituvchi KPI (attendance / payment / on-time / retention). Reyting uchun batch query — N+1 yo'q.
 *
 * <p>Hamma ko'rsatkich {@code [from, to]} ORALIG'I bo'yicha (ilgari to'lov ko'rsatkichlari bugungi
 * snapshot'dan olinardi — har oy, har trend nuqtasi bir xil chiqardi):
 * <ul>
 *   <li>davomat — oraliqdagi belgilangan davomat yozuvlari, (PRESENT + LATE) / hammasi;</li>
 *   <li>to'lov / o'z vaqtida — {@code billing_periods}: muddati ({@code due_date}) oraliqda kelgan
 *       muddatli davrlar ({@code CollectionsMetricsService} ta'rifi). Natijasi ma'lum bo'lganlari
 *       (to'langan yoki grace tugagan) maxraj; o'z vaqtida = {@code paid_on ≤ grace_until}. Hali grace
 *       ichida va to'lanmagan davr maxrajga kirmaydi. O'qituvchi — davr yozilgandagi
 *       ({@code billing_periods.teacher_id}), bo'lmasa guruhning hozirgisi;</li>
 *   <li>saqlab qolish — (oxirida ochiq + bitirgan) / (… + ketgan), ketish — churn sabablari.</li>
 * </ul>
 * Maxraj 0 bo'lsa ko'rsatkich {@code null} ("ma'lumot yetarli emas"), 100% emas.
 *
 * <p>Oy bo'yicha ({@code ?month=YYYY-MM}): joriy oy — jonli (oy boshidan bugungacha), yopilgan oy —
 * {@code teacher_kpi_monthly} snapshot'idan; snapshot yo'q bo'lsa jonli hisob ({@code source = LIVE}).
 */
@Service
@RequiredArgsConstructor
public class TeacherKpiService {

    public static final String SOURCE_LIVE = "LIVE";
    public static final String SOURCE_SNAPSHOT = "SNAPSHOT";

    private static final List<AttendanceStatus> PRESENT_STATUSES =
        List.of(AttendanceStatus.PRESENT, AttendanceStatus.LATE);
    private static final Set<BillingPeriodStatus> COLLECTIBLE =
        Set.of(BillingPeriodStatus.CHARGED, BillingPeriodStatus.PARTIALLY_REFUNDED);
    private static final int DEFAULT_TREND_MONTHS = 6;
    /** buildTrend oylik rejimda shundan ortiq oyni qirqadi; /kpi/trend ham shu chegarani tekshiradi. */
    public static final int MAX_TREND_MONTHS = 12;
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ISO_LOCAL_DATE;

    private final TeacherRepository teacherRepository;
    private final GroupRepository groupRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final AttendanceRepository attendanceRepository;
    private final BillingPeriodRepository billingPeriodRepository;
    private final TeacherKpiMonthlyRepository monthlyRepository;
    private final com.crm.billing.BillingStatusService billingStatusService;

    /** Bitta o'qituvchi, bitta oraliq — xom sonlar (foizlar shulardan). */
    public record Counts(long attendancePresent, long attendanceTotal,
                         long periodsDecided, long periodsPaid, long periodsOnTime, long periodsPending,
                         long openAtEnd, long graduated, long churned) {
        public static final Counts EMPTY = new Counts(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /** {@code ?month=YYYY-MM} → oraliq. Joriy oy: oy boshidan bugungacha; yopilgan: butun oy. */
    public record MonthRange(YearMonth month, LocalDate from, LocalDate to, boolean closed) {
        public String label() {
            return month.format(MONTH_LABEL);
        }
    }

    // ── oy ──────────────────────────────────────────────────────────────

    /** null → null; noto'g'ri format yoki kelajak oy — 400. */
    public MonthRange resolveMonth(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        YearMonth ym;
        try {
            ym = YearMonth.parse(raw.trim(), MONTH_LABEL);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("month formati YYYY-MM bo'lishi kerak: " + raw);
        }
        return monthRange(ym);
    }

    public MonthRange monthRange(YearMonth ym) {
        LocalDate today = billingStatusService.today();
        YearMonth current = YearMonth.from(today);
        if (ym.isAfter(current)) {
            throw new BadRequestException("Kelajak oy uchun KPI yo'q: " + ym.format(MONTH_LABEL));
        }
        boolean closed = ym.isBefore(current);
        return new MonthRange(ym, ym.atDay(1), closed ? ym.atEndOfMonth() : today, closed);
    }

    // ── skorlar ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public TeacherKpiScoresDto computeScores(Long teacherId, LocalDate from, LocalDate to) {
        Map<Long, TeacherKpiScoresDto> all = computeScoresForTeachers(List.of(teacherId), from, to);
        return all.getOrDefault(teacherId, toScores(Counts.EMPTY));
    }

    /** Bir nechta o'qituvchi uchun bir xil oraliqdagi jonli skorlar — batch query. */
    @Transactional(readOnly = true)
    public Map<Long, TeacherKpiScoresDto> computeScoresForTeachers(
            List<Long> teacherIds, LocalDate from, LocalDate to) {
        Map<Long, TeacherKpiScoresDto> result = new HashMap<>();
        if (teacherIds == null || teacherIds.isEmpty()) {
            return result;
        }
        Map<Long, Counts> counts = computeCounts(from, to, billingStatusService.today());
        for (Long id : teacherIds) {
            result.put(id, toScores(counts.getOrDefault(id, Counts.EMPTY)));
        }
        return result;
    }

    /** Oy bo'yicha: yopilgan oy — snapshot (bo'lsa), aks holda jonli. */
    @Transactional(readOnly = true)
    public TeacherKpiScoresDto scoresForMonth(Long teacherId, MonthRange range) {
        return scoresForMonth(List.of(teacherId), range).get(teacherId);
    }

    @Transactional(readOnly = true)
    public Map<Long, TeacherKpiScoresDto> scoresForMonth(List<Long> teacherIds, MonthRange range) {
        Map<Long, TeacherKpiScoresDto> result = new HashMap<>();
        List<Long> live = new ArrayList<>();
        if (range.closed()) {
            Map<Long, TeacherKpiMonthly> snaps = new HashMap<>();
            for (TeacherKpiMonthly m : monthlyRepository.findByMonthStart(range.from())) {
                snaps.put(m.getTeacherId(), m);
            }
            for (Long id : teacherIds) {
                TeacherKpiMonthly m = snaps.get(id);
                if (m != null) {
                    result.put(id, fromSnapshot(m));
                } else {
                    live.add(id);
                }
            }
        } else {
            live.addAll(teacherIds);
        }
        if (!live.isEmpty()) {
            Map<Long, Counts> counts = computeCounts(range.from(), range.to(), billingStatusService.today());
            for (Long id : live) {
                TeacherKpiScoresDto s = toScores(counts.getOrDefault(id, Counts.EMPTY));
                result.put(id, s);
            }
        }
        result.values().forEach(s -> s.setMonth(range.label()));
        return result;
    }

    /**
     * Hamma o'qituvchi uchun xom sonlar ({@code [from, to]}; {@code asOf} — to'lov holati qaysi
     * kunga: shu kungacha to'langan / grace shu kundan oldin tugagan davrlar "natijasi ma'lum").
     */
    @Transactional(readOnly = true)
    public Map<Long, Counts> computeCounts(LocalDate from, LocalDate to, LocalDate asOf) {
        Map<Long, long[]> acc = new HashMap<>();

        for (Object[] row : attendanceRepository.countAttendanceStatsGroupedByTeacher(from, to, PRESENT_STATUSES)) {
            if (row[0] == null) continue;
            long[] a = acc.computeIfAbsent(((Number) row[0]).longValue(), k -> new long[9]);
            a[0] += num(row[1]);
            a[1] += num(row[2]);
        }

        int graceDays = billingStatusService.graceDays();
        for (Object[] row : billingPeriodRepository.findDueForTeacherKpi(from, to, COLLECTIBLE)) {
            if (row[0] == null) continue;
            BigDecimal amount = row[4] != null ? (BigDecimal) row[4] : BigDecimal.ZERO;
            BigDecimal refunded = row[5] != null ? (BigDecimal) row[5] : BigDecimal.ZERO;
            if (amount.subtract(refunded).signum() <= 0) {
                continue;
            }
            LocalDate due = (LocalDate) row[1];
            LocalDate grace = row[2] != null ? (LocalDate) row[2] : due.plusDays(graceDays);
            LocalDate paidOn = (LocalDate) row[3];
            long[] a = acc.computeIfAbsent(((Number) row[0]).longValue(), k -> new long[9]);
            if (paidOn != null && !paidOn.isAfter(asOf)) {
                a[2]++;
                a[3]++;
                if (!paidOn.isAfter(grace)) {
                    a[4]++;
                }
            } else if (asOf.isAfter(grace)) {
                a[2]++;
            } else {
                a[5]++;
            }
        }

        for (Object[] row : studentGroupRepository.countRetentionStatsGroupedByTeacher(from, to)) {
            if (row[0] == null) continue;
            long[] a = acc.computeIfAbsent(((Number) row[0]).longValue(), k -> new long[9]);
            a[6] += num(row[1]);
            a[7] += num(row[2]);
            a[8] += num(row[3]);
        }

        Map<Long, Counts> out = new HashMap<>();
        acc.forEach((id, a) -> out.put(id, new Counts(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], a[8])));
        return out;
    }

    /** Xom sonlar → foizlar. Maxraj 0 → null. */
    public static TeacherKpiScoresDto toScores(Counts c) {
        Double attendanceRate = percent(c.attendancePresent(), c.attendanceTotal());
        Double paymentRate = percent(c.periodsPaid(), c.periodsDecided());
        Double onTimePaymentRate = percent(c.periodsOnTime(), c.periodsDecided());
        long retained = c.openAtEnd() + c.graduated();
        Double retentionRate = percent(retained, retained + c.churned());
        Double overall = overall(attendanceRate, paymentRate, onTimePaymentRate, retentionRate);
        return TeacherKpiScoresDto.builder()
            .attendanceRate(attendanceRate)
            .paymentRate(paymentRate)
            .onTimePaymentRate(onTimePaymentRate)
            .retentionRate(retentionRate)
            .overallScore(overall)
            .insufficientData(overall == null)
            .source(SOURCE_LIVE)
            .attendancePresent(c.attendancePresent())
            .attendanceTotal(c.attendanceTotal())
            .periodsDecided(c.periodsDecided())
            .periodsPaid(c.periodsPaid())
            .periodsOnTime(c.periodsOnTime())
            .periodsPending(c.periodsPending())
            .openAtEnd(c.openAtEnd())
            .graduated(c.graduated())
            .churned(c.churned())
            .build();
    }

    static TeacherKpiScoresDto fromSnapshot(TeacherKpiMonthly m) {
        return TeacherKpiScoresDto.builder()
            .attendanceRate(m.getAttendanceRate())
            .paymentRate(m.getPaymentRate())
            .onTimePaymentRate(m.getOnTimePaymentRate())
            .retentionRate(m.getRetentionRate())
            .overallScore(m.getOverallScore())
            .insufficientData(Boolean.TRUE.equals(m.getInsufficientData()))
            .month(YearMonth.from(m.getMonthStart()).format(MONTH_LABEL))
            .source(SOURCE_SNAPSHOT)
            .computedAt(m.getComputedAt())
            .attendancePresent(lng(m.getAttendancePresent()))
            .attendanceTotal(lng(m.getAttendanceTotal()))
            .periodsDecided(lng(m.getPeriodsDecided()))
            .periodsPaid(lng(m.getPeriodsPaid()))
            .periodsOnTime(lng(m.getPeriodsOnTime()))
            .periodsPending(lng(m.getPeriodsPending()))
            .openAtEnd(lng(m.getOpenAtEnd()))
            .graduated(lng(m.getGraduated()))
            .churned(lng(m.getChurned()))
            .build();
    }

    // ── trend ───────────────────────────────────────────────────────────

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

        // monthly: from..to oraligidagi oylar (default oxirgi 6 oy); yopilgan oy — snapshot'dan
        YearMonth current = YearMonth.from(billingStatusService.today());
        List<YearMonth> months = resolveTrendMonths(from, to);
        for (YearMonth ym : months) {
            TeacherKpiScoresDto scores;
            if (ym.isBefore(current)) {
                scores = scoresForMonth(teacherId, monthRange(ym));
            } else {
                LocalDate mFrom = ym.atDay(1);
                LocalDate mTo = ym.equals(YearMonth.from(to)) ? to : ym.atEndOfMonth();
                if (mTo.isBefore(mFrom)) {
                    mTo = mFrom;
                }
                scores = computeScores(teacherId, mFrom, mTo);
            }
            trend.add(TeacherKpiTrendPointDto.builder()
                .label(ym.format(MONTH_LABEL))
                .overallScore(scores.getOverallScore())
                .insufficientData(Boolean.TRUE.equals(scores.getInsufficientData()))
                .build());
        }
        return trend;
    }

    // ── reyting ─────────────────────────────────────────────────────────

    /** Oraliq (from/to) bo'yicha — jonli. */
    @Transactional(readOnly = true)
    public TeacherKpiRankingResponse getRanking(String period, LocalDate from, LocalDate to) {
        List<Teacher> teachers = teacherRepository.findByIsActiveTrue();
        Map<Long, TeacherKpiScoresDto> scores = computeScoresForTeachers(
            teachers.stream().map(Teacher::getId).toList(), from, to);
        return ranking(normalizePeriod(period), from, to, null, teachers, scores);
    }

    /** Oy bo'yicha: joriy — jonli, yopilgan — snapshot'dan (yo'qlari jonli). */
    @Transactional(readOnly = true)
    public TeacherKpiRankingResponse getRanking(String period, MonthRange range) {
        List<Teacher> teachers = teacherRepository.findByIsActiveTrue();
        Map<Long, TeacherKpiScoresDto> scores = scoresForMonth(
            teachers.stream().map(Teacher::getId).toList(), range);
        return ranking(normalizePeriod(period), range.from(), range.to(), range, teachers, scores);
    }

    private TeacherKpiRankingResponse ranking(String period, LocalDate from, LocalDate to, MonthRange range,
                                              List<Teacher> teachers, Map<Long, TeacherKpiScoresDto> scores) {
        Map<Long, Integer> groupCounts = toIntMap(groupRepository.countGroupsGroupedByTeacher());
        Map<Long, Integer> snapshotGroupCounts = new HashMap<>();
        if (range != null && range.closed()) {
            for (TeacherKpiMonthly m : monthlyRepository.findByMonthStart(range.from())) {
                snapshotGroupCounts.put(m.getTeacherId(), m.getGroupCount());
            }
        }

        List<TeacherKpiRankingItemDto> items = new ArrayList<>();
        for (Teacher t : teachers) {
            TeacherKpiScoresDto s = scores.getOrDefault(t.getId(), toScores(Counts.EMPTY));
            boolean snapshot = SOURCE_SNAPSHOT.equals(s.getSource());
            items.add(TeacherKpiRankingItemDto.builder()
                .teacherId(t.getId())
                .teacherName(fullName(t))
                .photoUrl(t.getPhotoUrl())
                .groupCount(snapshot ? snapshotGroupCounts.getOrDefault(t.getId(), 0)
                    : groupCounts.getOrDefault(t.getId(), 0))
                .studentCount(s.getOpenAtEnd() != null ? s.getOpenAtEnd().intValue() : 0)
                .attendanceRate(s.getAttendanceRate())
                .paymentRate(s.getPaymentRate())
                .onTimePaymentRate(s.getOnTimePaymentRate())
                .retentionRate(s.getRetentionRate())
                .overallScore(s.getOverallScore())
                .insufficientData(Boolean.TRUE.equals(s.getInsufficientData()))
                .source(s.getSource())
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

        long snapshots = items.stream().filter(i -> SOURCE_SNAPSHOT.equals(i.getSource())).count();
        String source = snapshots == 0 ? SOURCE_LIVE
            : (snapshots == items.size() ? SOURCE_SNAPSHOT : "MIXED");
        return TeacherKpiRankingResponse.builder()
            .period(period)
            .from(from)
            .to(to)
            .month(range != null ? range.label() : null)
            .source(source)
            .teachers(items)
            .build();
    }

    // ── yordamchilar ────────────────────────────────────────────────────

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

    /** Maxraj 0 → null ("ma'lumot yetarli emas"). */
    static Double percent(long part, long whole) {
        return whole > 0 ? round1(part * 100.0 / whole) : null;
    }

    /** Teng og'irlik: faqat hisoblangan (null bo'lmagan) ko'rsatkichlar o'rtachasi; hammasi null — null. */
    static Double overall(Double... parts) {
        double sum = 0;
        int n = 0;
        for (Double p : parts) {
            if (p != null) {
                sum += p;
                n++;
            }
        }
        return n > 0 ? round1(sum / n) : null;
    }

    private static String fullName(Teacher t) {
        return ((t.getFirstName() != null ? t.getFirstName() : "")
            + " "
            + (t.getLastName() != null ? t.getLastName() : "")).trim();
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static long num(Object v) {
        return v != null ? ((Number) v).longValue() : 0L;
    }

    private static Long lng(Integer v) {
        return v != null ? v.longValue() : 0L;
    }

    private static Map<Long, Integer> toIntMap(List<Object[]> rows) {
        Map<Long, Integer> map = new HashMap<>();
        for (Object[] row : rows) {
            if (row[0] == null) continue;
            map.put(((Number) row[0]).longValue(), ((Number) row[1]).intValue());
        }
        return map;
    }
}
