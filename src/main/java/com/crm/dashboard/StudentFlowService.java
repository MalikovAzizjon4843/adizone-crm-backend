package com.crm.dashboard;

import com.crm.billing.EnrollmentPricing;
import com.crm.billing.Money;
import com.crm.dashboard.StudentFlowDtos.FlowRow;
import com.crm.dashboard.StudentFlowDtos.Report;
import com.crm.dashboard.StudentFlowDtos.Retention;
import com.crm.dashboard.StudentFlowDtos.StudentRow;
import com.crm.entity.Group;
import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.entity.Teacher;
import com.crm.entity.enums.BalanceTransactionType;
import com.crm.entity.enums.BillingPeriodStatus;
import com.crm.entity.enums.ExitReasonCode;
import com.crm.entity.enums.GroupStatus;
import com.crm.exception.CodedException;
import com.crm.service.StudentRosterService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * O'quvchi oqimi va retention — o'qituvchi / guruh kesimida, oy bo'yicha ({@code GET /api/analytics/student-flow}).
 *
 * <p>Birlik — TO'LOVLI yozilma (sinov emas). Yozilma to'lovli bo'lgan kun: sinovdan o'tgan bo'lsa
 * {@code trial_converted_at}, aks holda {@code join_date}; hali sinovda / sinovda yopilgan yozilma hisobga kirmaydi.
 * Yopilgan kun: {@code leave_date ?? exit_date ?? frozen_from}; ochiq qolgan yozilma TUGAGAN/BEKOR guruhda —
 * guruhning {@code end_date} si (sanasi yo'q bo'lsa yozilma tashlanadi).
 *
 * <p>Chiqish turlari — mavjud ta'riflar ({@link RetentionMetricsService#exits}, o'qituvchi KPI "saqlab qolish"):
 * <ul>
 *   <li>{@code frozen} — muzlatish ({@code frozen_from} yoki FROZEN / AUTO_ARCHIVE);</li>
 *   <li>{@code transferredOut} — TRANSFERRED (yo'qotish emas);</li>
 *   <li>{@code completed} — GRADUATED (kod yoki eski matn), yoki guruh COMPLETED/CANCELLED va chiqish
 *       {@code end_date} dan keyin / ochiq qolgan (yo'qotish emas);</li>
 *   <li>qolgani — ketish ({@link ExitReasonCode#isChurn()}): aniq sabab ({@link ExitReasonCode#SPECIFIC_CHURN}) yoki
 *       izoh / erkin matn yozilgan bo'lsa {@code leftWithReason}, aks holda {@code leftNoReason}.</li>
 * </ul>
 * Ko'chib kelgan ({@code transferredIn}) — shu o'quvchining TRANSFERRED bilan yopilgan to'lovli yozilmasidan keyin
 * ochilgan birinchi yozilma (± 31 kun ichida); qolgan yangi to'lovli yozilmalar — {@code joined}.
 *
 * <p>Retention: real to'lov (PAYMENT / TRANSFER_IN krediti) bilan to'liq yopilgan, shu oyda boshlangan MONTHLY davri
 * bor o'quvchilar; keyingi oyda boshlangan shunday davri borlar — saqlanganlar (istalgan guruhda: ko'chish yo'qotish
 * emas). PER_LESSON yozilmalar davrsiz — retention'ga kirmaydi.
 *
 * <p>Teacher — guruhning HOZIRGI o'qituvchisi (KPI "saqlab qolish" bilan bir xil). Kesh — 60 s (dashboard kabi).
 */
@Service
public class StudentFlowService {

    public static final int TRANSFER_PAIR_DAYS = 31;
    private static final int CHUNK = 1000;
    private static final Set<GroupStatus> FINISHED = EnumSet.of(GroupStatus.COMPLETED, GroupStatus.CANCELLED);
    private static final Set<BillingPeriodStatus> COLLECTIBLE =
        EnumSet.of(BillingPeriodStatus.CHARGED, BillingPeriodStatus.PARTIALLY_REFUNDED);
    private static final Set<BalanceTransactionType> REAL_MONEY =
        EnumSet.of(BalanceTransactionType.PAYMENT, BalanceTransactionType.TRANSFER_IN);
    /** Sabab emas, umumiy belgi bo'lgan eski {@code exit_reason} matnlari. */
    private static final Set<String> GENERIC_TEXT = Set.of("LEFT", "OTHER", "SUSPENDED");
    private static final String UNASSIGNED = "Biriktirilmagan";

    public enum Category {
        JOINED("joined"), TRANSFERRED_IN("transferredIn"), COMPLETED("completed"),
        LEFT_WITH_REASON("leftWithReason"), LEFT_NO_REASON("leftNoReason"),
        TRANSFERRED_OUT("transferredOut"), FROZEN("frozen");

        public final String key;

        Category(String key) {
            this.key = key;
        }

        boolean isLost() {
            return this == LEFT_WITH_REASON || this == LEFT_NO_REASON;
        }
    }

    /** Bitta yozilmaning shu oydagi holati (keshlanadi — entity emas). */
    record Fact(Long sgId, Long studentId, String studentName, String phone, Long groupId, String groupName,
                String groupStatus, Long teacherId, String teacherName, boolean startActive, boolean endActive,
                Category entry, LocalDate entryDate, Category exit, LocalDate exitDate, ExitReasonCode reasonCode,
                String notes, BigDecimal monthlyPrice, boolean paidInMonth, boolean paidNextMonth) {
    }

    record Analysis(YearMonth month, LocalDate from, LocalDate to, LocalDate asOf, boolean monthFinal,
                    boolean retentionFinal, List<Fact> facts) {
    }

    private final DashboardQueries queries;
    private final Clock billingClock;
    private final Cache<String, Analysis> cache;

    public StudentFlowService(DashboardQueries queries, Clock billingClock, DashboardProperties properties) {
        this.queries = queries;
        this.billingClock = billingClock;
        this.cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(Math.max(1, properties.getCacheSeconds())))
            .maximumSize(100)
            .build();
    }

    // ── API ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Report report(String month, Long teacherId, Long groupId) {
        Analysis a = analysis(month);
        List<Fact> facts = a.facts().stream().filter(scope(teacherId, groupId)).toList();

        Map<String, List<Fact>> byTeacher = new LinkedHashMap<>();
        facts.stream()
            .sorted(Comparator.comparing((Fact f) -> f.teacherId() == null)
                .thenComparing(f -> f.teacherName() != null ? f.teacherName() : "", String.CASE_INSENSITIVE_ORDER)
                .thenComparing(f -> f.teacherId() != null ? f.teacherId() : 0L)
                .thenComparing(f -> f.groupName() != null ? f.groupName() : "", String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Fact::groupId))
            .forEach(f -> byTeacher.computeIfAbsent(String.valueOf(f.teacherId()), k -> new ArrayList<>()).add(f));

        List<FlowRow> teachers = new ArrayList<>();
        for (List<Fact> tf : byTeacher.values()) {
            Map<Long, List<Fact>> byGroup = new LinkedHashMap<>();
            tf.forEach(f -> byGroup.computeIfAbsent(f.groupId(), k -> new ArrayList<>()).add(f));
            List<FlowRow> groups = new ArrayList<>();
            for (List<Fact> gf : byGroup.values()) {
                Fact first = gf.get(0);
                FlowRow row = row(gf, a, first.teacherId(), first.teacherName(), first.groupId(), first.groupName(),
                    first.groupStatus(), null);
                if (!isEmpty(row)) {
                    groups.add(row);
                }
            }
            if (groups.isEmpty()) {
                continue;
            }
            Fact first = tf.get(0);
            teachers.add(row(tf, a, first.teacherId(), first.teacherName(), null, null, null, groups));
        }
        return new Report(a.month().toString(), a.from(), a.to(), a.asOf(), a.monthFinal(),
            row(facts, a, null, null, null, null, null, null), teachers);
    }

    @Transactional(readOnly = true)
    public List<StudentRow> students(String month, Long teacherId, Long groupId, String category) {
        Predicate<Fact> filter = categoryFilter(category);
        Analysis a = analysis(month);
        return a.facts().stream()
            .filter(scope(teacherId, groupId))
            .filter(filter)
            .map(f -> {
                boolean entry = f.entry() != null && filter.test(entryOnly(f));
                Category c = entry ? f.entry() : f.exit();
                return new StudentRow(f.studentId(), f.studentName(), f.phone(), f.sgId(), f.groupId(), f.groupName(),
                    f.teacherId(), f.teacherName(), c.key, entry ? f.entryDate() : f.exitDate(),
                    entry || f.reasonCode() == null ? null : f.reasonCode().name(),
                    entry || f.reasonCode() == null ? null : f.reasonCode().getLabel(),
                    entry ? null : f.notes(), f.monthlyPrice());
            })
            .sorted(Comparator.comparing(StudentRow::date).thenComparing(StudentRow::studentName,
                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)).thenComparing(StudentRow::studentGroupId))
            .toList();
    }

    public void invalidate() {
        cache.invalidateAll();
    }

    public static YearMonth parseMonth(String month, LocalDate today) {
        YearMonth current = YearMonth.from(today);
        if (month == null || month.isBlank()) {
            return current;
        }
        try {
            YearMonth m = YearMonth.parse(month.trim());
            if (m.isAfter(current)) {
                throw CodedException.badRequest("dashboard.param.invalid", "month");
            }
            return m;
        } catch (DateTimeParseException e) {
            throw CodedException.badRequest("dashboard.param.invalid", "month");
        }
    }

    // ── hisob ───────────────────────────────────────────────────────────

    private Analysis analysis(String month) {
        LocalDate today = LocalDate.now(billingClock);
        YearMonth m = parseMonth(month, today);
        return cache.get(m + "|" + today, k -> compute(m, today));
    }

    Analysis compute(YearMonth month, LocalDate today) {
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();
        LocalDate asOf = to.isAfter(today) ? today : to;
        YearMonth next = month.plusMonths(1);

        List<StudentGroup> sgs = queries.em().createQuery("""
                SELECT sg FROM StudentGroup sg JOIN FETCH sg.student JOIN FETCH sg.group g
                LEFT JOIN FETCH g.teacher LEFT JOIN FETCH g.course
                WHERE (sg.joinDate IS NULL OR sg.joinDate <= :asOf)
                  AND (COALESCE(sg.leaveDate, sg.exitDate, sg.frozenFrom) IS NULL
                       OR COALESCE(sg.leaveDate, sg.exitDate, sg.frozenFrom) >= :from)
                """, StudentGroup.class)
            .setParameter("asOf", asOf).setParameter("from", from).getResultList();

        // Kirishlar: shu oyda to'lovli bo'lganlar — ko'chib kelganmi (o'quvchining barcha yozilmalari bo'yicha)
        Set<Long> entrants = new HashSet<>();
        for (StudentGroup sg : sgs) {
            LocalDate p = payingFrom(sg);
            if (p != null && !p.isBefore(from) && !p.isAfter(asOf)) {
                entrants.add(sg.getStudent().getId());
            }
        }
        Set<Long> transferTargets = transferTargets(entrants);

        // Retention: real pul bilan yopilgan davrlar (shu oy va keyingi oy boshlangan)
        Set<Long> paidSgInMonth = new HashSet<>();
        Set<Long> paidStudentsNext = new HashSet<>();
        for (Object[] r : queries.em().createQuery("""
                SELECT bp.studentGroupId, sg.student.id, bp.periodStart
                FROM BillingPeriod bp, StudentGroup sg, BalanceTransaction tx
                WHERE sg.id = bp.studentGroupId AND tx.id = bp.paidTxId
                  AND bp.periodStart BETWEEN :from AND :to
                  AND bp.paidOn IS NOT NULL AND bp.chargeTxId IS NOT NULL
                  AND bp.status IN :statuses
                  AND bp.amount > COALESCE(bp.refundedAmount, 0)
                  AND tx.type IN :types
                  AND (sg.billingHold IS NULL OR sg.billingHold = false)
                """, Object[].class)
            .setParameter("from", from).setParameter("to", next.atEndOfMonth())
            .setParameter("statuses", COLLECTIBLE).setParameter("types", REAL_MONEY)
            .getResultList()) {
            LocalDate start = (LocalDate) r[2];
            if (YearMonth.from(start).equals(month)) {
                paidSgInMonth.add(((Number) r[0]).longValue());
            } else {
                paidStudentsNext.add(((Number) r[1]).longValue());
            }
        }

        List<Fact> facts = new ArrayList<>();
        for (StudentGroup sg : sgs) {
            Fact f = fact(sg, from, asOf, transferTargets, paidSgInMonth, paidStudentsNext);
            if (f != null) {
                facts.add(f);
            }
        }
        return new Analysis(month, from, to, asOf, today.isAfter(to), today.isAfter(next.atEndOfMonth()), facts);
    }

    private Fact fact(StudentGroup sg, LocalDate from, LocalDate asOf, Set<Long> transferTargets,
                      Set<Long> paidSgInMonth, Set<Long> paidStudentsNext) {
        Group g = sg.getGroup();
        LocalDate p = payingFrom(sg);
        LocalDate c = closedOn(sg);
        boolean implicitEnd = false;
        if (c == null && g != null && g.getStatus() != null && FINISHED.contains(g.getStatus())) {
            if (g.getEndDate() == null) {
                return null;
            }
            c = g.getEndDate();
            implicitEnd = true;
        }
        if (c == null && !Boolean.TRUE.equals(sg.getIsActive())) {
            return null;
        }
        boolean paying = p != null && (c == null || !c.isBefore(p));
        boolean paidInMonth = paidSgInMonth.contains(sg.getId());
        if (!paying && !paidInMonth) {
            return null;
        }
        boolean startActive = paying && p.isBefore(from) && (c == null || !c.isBefore(from));
        boolean endActive = paying && !p.isAfter(asOf) && (c == null || c.isAfter(asOf));
        Category entry = null;
        if (paying && !p.isBefore(from) && !p.isAfter(asOf)) {
            entry = transferTargets.contains(sg.getId()) ? Category.TRANSFERRED_IN : Category.JOINED;
        }
        Category exit = null;
        ExitReasonCode code = null;
        String notes = null;
        if (paying && c != null && !c.isBefore(from) && !c.isAfter(asOf)) {
            code = implicitEnd ? null : StudentRosterService.exitCode(sg);
            exit = exitCategory(sg, g, c, code, implicitEnd);
            notes = notes(sg);
            if (exit.isLost() && (code == null || !ExitReasonCode.SPECIFIC_CHURN.contains(code))) {
                code = ExitReasonCode.OTHER;
            }
        }
        if (!startActive && !endActive && entry == null && exit == null && !paidInMonth) {
            return null;
        }
        Student s = sg.getStudent();
        Teacher t = g != null ? g.getTeacher() : null;
        return new Fact(sg.getId(), s.getId(), FunnelMetricsService.name(s.getFirstName(), s.getLastName()),
            s.getPhone(), g != null ? g.getId() : null, g != null ? g.getGroupName() : null,
            g != null && g.getStatus() != null ? g.getStatus().name() : null,
            t != null ? t.getId() : null, t != null ? StudentRosterService.teacherName(t) : UNASSIGNED,
            startActive, endActive, entry, entry != null ? p : null, exit, exit != null ? c : null, code, notes,
            monthlyPrice(sg), paidInMonth, paidStudentsNext.contains(s.getId()));
    }

    private static Category exitCategory(StudentGroup sg, Group g, LocalDate closed, ExitReasonCode code,
                                         boolean implicitEnd) {
        if (implicitEnd) {
            return Category.COMPLETED;
        }
        if (sg.getFrozenFrom() != null || code == ExitReasonCode.FROZEN || code == ExitReasonCode.AUTO_ARCHIVE) {
            return Category.FROZEN;
        }
        if (code == ExitReasonCode.TRANSFERRED) {
            return Category.TRANSFERRED_OUT;
        }
        if (code == ExitReasonCode.GRADUATED || groupEndedBy(g, closed)) {
            return Category.COMPLETED;
        }
        return hasReason(sg, code) ? Category.LEFT_WITH_REASON : Category.LEFT_NO_REASON;
    }

    /** Guruh tugagan (COMPLETED/CANCELLED) va yozilma uning {@code end_date} sidan keyin yopilgan. */
    private static boolean groupEndedBy(Group g, LocalDate closed) {
        return g != null && g.getStatus() != null && FINISHED.contains(g.getStatus())
            && g.getEndDate() != null && !closed.isBefore(g.getEndDate());
    }

    private static boolean hasReason(StudentGroup sg, ExitReasonCode code) {
        if (code != null && ExitReasonCode.SPECIFIC_CHURN.contains(code)) {
            return true;
        }
        if (notBlank(sg.getExitNotes())) {
            return true;
        }
        String text = sg.getExitReason();
        return notBlank(text) && ExitReasonCode.fromLegacy(text) == ExitReasonCode.OTHER
            && !GENERIC_TEXT.contains(text.trim().toUpperCase(Locale.ROOT));
    }

    private static String notes(StudentGroup sg) {
        if (notBlank(sg.getExitNotes())) {
            return sg.getExitNotes();
        }
        String text = sg.getExitReason();
        return notBlank(text) && ExitReasonCode.fromLegacy(text) == ExitReasonCode.OTHER
            && !GENERIC_TEXT.contains(text.trim().toUpperCase(Locale.ROOT)) ? text : null;
    }

    /** To'lovli bo'lgan kun: sinov — null; sinovdan o'tgan — {@code trial_converted_at}; aks holda {@code join_date}. */
    static LocalDate payingFrom(StudentGroup sg) {
        if (Boolean.TRUE.equals(sg.getIsTrial())) {
            return null;
        }
        if (sg.getTrialConvertedAt() != null) {
            return sg.getTrialConvertedAt();
        }
        if (sg.getJoinDate() != null) {
            return sg.getJoinDate();
        }
        return sg.getCreatedAt() != null ? sg.getCreatedAt().toLocalDate() : null;
    }

    static LocalDate closedOn(StudentGroup sg) {
        if (sg.getLeaveDate() != null) {
            return sg.getLeaveDate();
        }
        if (sg.getExitDate() != null) {
            return sg.getExitDate();
        }
        if (sg.getFrozenFrom() != null) {
            return sg.getFrozenFrom();
        }
        if (!Boolean.TRUE.equals(sg.getIsActive()) && sg.getUpdatedAt() != null) {
            return sg.getUpdatedAt().toLocalDate();
        }
        return null;
    }

    private static BigDecimal monthlyPrice(StudentGroup sg) {
        try {
            return Money.normalize(EnrollmentPricing.effectiveMonthlyFee(sg));
        } catch (IllegalArgumentException e) {
            return Money.normalize(EnrollmentPricing.monthlyFee(sg));
        }
    }

    /**
     * TRANSFERRED bilan yopilgan to'lovli yozilma → o'sha o'quvchining undan keyin ochilgan (id katta) va
     * {@value #TRANSFER_PAIR_DAYS} kun ichida boshlangan birinchi yozilmasi.
     */
    private Set<Long> transferTargets(Collection<Long> studentIds) {
        Set<Long> out = new HashSet<>();
        if (studentIds.isEmpty()) {
            return out;
        }
        Map<Long, List<StudentGroup>> byStudent = new HashMap<>();
        List<Long> ids = List.copyOf(studentIds);
        for (int i = 0; i < ids.size(); i += CHUNK) {
            queries.em().createQuery("SELECT sg FROM StudentGroup sg WHERE sg.student.id IN :ids", StudentGroup.class)
                .setParameter("ids", ids.subList(i, Math.min(ids.size(), i + CHUNK))).getResultList()
                .forEach(sg -> byStudent.computeIfAbsent(sg.getStudent().getId(), k -> new ArrayList<>()).add(sg));
        }
        for (List<StudentGroup> all : byStudent.values()) {
            all.sort(Comparator.comparing(StudentGroup::getId));
            for (StudentGroup x : all) {
                LocalDate closed = closedOn(x);
                LocalDate paying = payingFrom(x);
                if (closed == null || paying == null || closed.isBefore(paying)
                        || StudentRosterService.exitCode(x) != ExitReasonCode.TRANSFERRED) {
                    continue;
                }
                all.stream()
                    .filter(y -> y.getId() > x.getId() && !out.contains(y.getId()) && y.getJoinDate() != null
                        && Math.abs(ChronoUnit.DAYS.between(closed, y.getJoinDate())) <= TRANSFER_PAIR_DAYS)
                    .findFirst()
                    .ifPresent(y -> out.add(y.getId()));
            }
        }
        return out;
    }

    // ── agregat ─────────────────────────────────────────────────────────

    private static FlowRow row(List<Fact> facts, Analysis a, Long teacherId, String teacherName, Long groupId,
                               String groupName, String groupStatus, List<FlowRow> groups) {
        long startActive = 0, joined = 0, transferredIn = 0, completed = 0, leftWithReason = 0, leftNoReason = 0;
        long transferredOut = 0, frozen = 0, endActive = 0;
        BigDecimal lostRevenue = BigDecimal.ZERO;
        Map<String, Long> byReason = new TreeMap<>();
        Set<Long> paid = new HashSet<>();
        Set<Long> retained = new HashSet<>();
        for (Fact f : facts) {
            if (f.startActive()) startActive++;
            if (f.endActive()) endActive++;
            if (f.entry() == Category.JOINED) joined++;
            if (f.entry() == Category.TRANSFERRED_IN) transferredIn++;
            if (f.exit() != null) {
                switch (f.exit()) {
                    case COMPLETED -> completed++;
                    case TRANSFERRED_OUT -> transferredOut++;
                    case FROZEN -> frozen++;
                    case LEFT_WITH_REASON -> leftWithReason++;
                    case LEFT_NO_REASON -> leftNoReason++;
                    default -> { }
                }
                if (f.exit() == Category.LEFT_WITH_REASON) {
                    byReason.merge(f.reasonCode() != null ? f.reasonCode().name() : ExitReasonCode.OTHER.name(),
                        1L, Long::sum);
                }
                if (f.exit().isLost()) {
                    lostRevenue = lostRevenue.add(Money.nz(f.monthlyPrice()));
                }
            }
            if (f.paidInMonth()) {
                paid.add(f.studentId());
                if (f.paidNextMonth()) {
                    retained.add(f.studentId());
                }
            }
        }
        long lost = leftWithReason + leftNoReason;
        long potential = startActive + joined + transferredIn - completed - transferredOut;
        Retention retention = new Retention(paid.size(), retained.size(), Ratios.percent(retained.size(), paid.size()),
            a.retentionFinal());
        return new FlowRow(teacherId, teacherName, groupId, groupName, groupStatus, startActive, joined, transferredIn,
            completed, leftWithReason, byReason, leftNoReason, transferredOut, frozen, endActive, lost, potential,
            Money.normalize(lostRevenue), retention, groups);
    }

    private static boolean isEmpty(FlowRow r) {
        return r.startActive() == 0 && r.endActive() == 0 && r.joined() == 0 && r.transferredIn() == 0
            && r.completed() == 0 && r.lost() == 0 && r.transferredOut() == 0 && r.frozen() == 0
            && r.retentionNextMonth().paidStudents() == 0;
    }

    private static Predicate<Fact> scope(Long teacherId, Long groupId) {
        return f -> (teacherId == null || teacherId.equals(f.teacherId()))
            && (groupId == null || groupId.equals(f.groupId()));
    }

    /** Drill-down kategoriyasi; noto'g'ri — 400 {@code dashboard.param.invalid}. */
    static Predicate<Fact> categoryFilter(String category) {
        if (category == null || category.isBlank()) {
            throw CodedException.badRequest("dashboard.param.invalid", "category");
        }
        String c = category.trim();
        if ("lost".equalsIgnoreCase(c)) {
            return f -> f.exit() != null && f.exit().isLost();
        }
        for (Category k : Category.values()) {
            if (k.key.equalsIgnoreCase(c) || k.name().equalsIgnoreCase(c)) {
                return switch (k) {
                    case JOINED, TRANSFERRED_IN -> f -> f.entry() == k;
                    default -> f -> f.exit() == k;
                };
            }
        }
        throw CodedException.badRequest("dashboard.param.invalid", "category");
    }

    /** Kirish kategoriyasi bo'yicha tekshiruv uchun — chiqishsiz nusxa. */
    private static Fact entryOnly(Fact f) {
        return new Fact(f.sgId(), f.studentId(), f.studentName(), f.phone(), f.groupId(), f.groupName(),
            f.groupStatus(), f.teacherId(), f.teacherName(), f.startActive(), f.endActive(), f.entry(), f.entryDate(),
            null, null, null, null, f.monthlyPrice(), f.paidInMonth(), f.paidNextMonth());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
