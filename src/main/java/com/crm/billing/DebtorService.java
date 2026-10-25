package com.crm.billing;

import com.crm.dto.response.DebtorsListResponse;
import com.crm.dto.response.ExpectedPaymentsResponse;
import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.PaymentStatus;
import com.crm.entity.enums.StudentStatus;
import com.crm.repository.StudentGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Qarzdorlar va kutilayotgan to'lovlar — YAGONA ta'rif (docs/design/billing-v2.md §4.5).
 *
 * <blockquote>Qarzdor = kamida bitta SG si OVERDUE bo'lgan o'quvchi.
 * Qarz = Σ max(0, −sg.balance) (shu o'quvchining OVERDUE va PENDING SG lari).</blockquote>
 *
 * Dashboard, analitika, {@code /debtors}, {@code /debtors/summary}, Telegram eslatma
 * hammasi shu klassni chaqiradi; KPI SQL'i {@link BillingStatusService#overdueBefore}
 * bilan bir xil shartni ishlatadi.
 */
@Service
@RequiredArgsConstructor
public class DebtorService {

    private final BillingStatusService statusService;
    private final StudentGroupRepository studentGroupRepository;
    private final com.crm.repository.BillingPeriodRepository periodRepository;
    private final com.crm.repository.AttendanceRepository attendanceRepository;
    private final com.crm.service.GroupScheduleService scheduleService;

    private static final java.util.EnumSet<com.crm.entity.enums.AttendanceStatus> ATTENDED =
        java.util.EnumSet.of(com.crm.entity.enums.AttendanceStatus.PRESENT, com.crm.entity.enums.AttendanceStatus.LATE);

    /**
     * Qarzdorlar ro'yxatidagi "bugun" holati (shu guruhda): davomat bo'lsa — uning holati; bo'lmasa bugun dars bor —
     * NOT_MARKED, dars yo'q — NO_LESSON. O'quvchi qatorida — guruhlari ichida eng "kelgan" (tartib — shu enum).
     */
    public enum TodayStatus {
        PRESENT, LATE, ABSENT, EXCUSED, NOT_MARKED, NO_LESSON
    }

    /**
     * Davomat ro'yxati uchun qarzdor belgisi — {@link #collect} bilan AYNAN bir xil ta'rif (§4.5, ACTIVE doirasi):
     * qarzdor = OVERDUE SG ({@link BillingStatusService#isOverdue}); qarz = Σ max(0, −balance) doiradagi qarzli SG lar.
     *
     * @param debtor           shu guruh yozilmasi OVERDUE
     * @param studentDebtor    o'quvchi qarzdor (istalgan guruhi bo'yicha — {@code /debtors} ro'yxatidagi bilan bir xil)
     * @param otherGroupDebt   boshqa guruhdagi yozilmasi OVERDUE
     * @param debtAmount       shu yozilma qarzi; {@code studentDebtAmount} — o'quvchining jami qarzi (ikkalasi ham
     *                         faqat qarzdor bo'lsa; summani kimga ko'rsatish — chaqiruvchida)
     */
    public record DebtFlag(boolean debtor, LocalDate debtSince, Long debtDays, BigDecimal debtAmount,
                           boolean studentDebtor, LocalDate studentDebtSince, Long studentDebtDays,
                           BigDecimal studentDebtAmount, boolean otherGroupDebt) {

        public static final DebtFlag NONE = new DebtFlag(false, null, null, null, false, null, null, null, false);
    }

    /**
     * Guruh davomati uchun qarzdor belgilari — BITTA so'rov ({@code findWithDebtByStudentIds}). Ro'yxatda yo'q
     * o'quvchi — {@link DebtFlag#NONE}.
     */
    @Transactional(readOnly = true)
    public Map<Long, DebtFlag> debtFlags(Long groupId, java.util.Collection<Long> studentIds, LocalDate today) {
        Map<Long, DebtFlag> out = new LinkedHashMap<>();
        if (studentIds == null || studentIds.isEmpty()) {
            return out;
        }
        Map<Long, List<StudentGroup>> byStudent = new LinkedHashMap<>();
        for (StudentGroup sg : studentGroupRepository.findWithDebtByStudentIds(studentIds)) {
            if (inScope(sg, Scope.ACTIVE)) {
                byStudent.computeIfAbsent(sg.getStudent().getId(), k -> new ArrayList<>()).add(sg);
            }
        }
        byStudent.forEach((studentId, sgs) -> {
            List<StudentGroup> overdue = sgs.stream().filter(sg -> statusService.isOverdue(sg, today)).toList();
            if (overdue.isEmpty()) {
                return;
            }
            LocalDate since = overdue.stream().map(StudentGroup::getDebtSince).min(Comparator.naturalOrder()).orElseThrow();
            BigDecimal total = sgs.stream().map(sg -> Money.nz(sg.getBalance()).negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            List<StudentGroup> here = overdue.stream().filter(sg -> sg.getGroup().getId().equals(groupId)).toList();
            LocalDate hereSince = here.stream().map(StudentGroup::getDebtSince).min(Comparator.naturalOrder()).orElse(null);
            BigDecimal hereDebt = here.isEmpty() ? null : here.stream().map(sg -> Money.nz(sg.getBalance()).negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            out.put(studentId, new DebtFlag(!here.isEmpty(), hereSince,
                hereSince != null ? ChronoUnit.DAYS.between(hereSince, today) : null, hereDebt,
                true, since, ChronoUnit.DAYS.between(since, today), total,
                overdue.stream().anyMatch(sg -> !sg.getGroup().getId().equals(groupId))));
        });
        return out;
    }

    public enum Scope {
        /** Faol yoki muzlatilgan o'quvchining ochiq SG lari (default, §13 #4). */
        ACTIVE,
        /** Yopilgan SG qarzlari ham. */
        ALL;

        public static Scope parse(String raw) {
            return raw != null && raw.trim().equalsIgnoreCase("ALL") ? ALL : ACTIVE;
        }
    }

    /**
     * {@code today}: null — hammasi; {@code present} — bugun kelgan qarzdorlar (PRESENT yoki LATE); yoki aniq
     * {@link TodayStatus} nomi (masalan {@code ABSENT}, {@code NOT_MARKED}).
     */
    public record Filter(Scope scope, Integer minDays, Long groupId, Integer page, Integer size, String today) {
        public Filter(Scope scope, Integer minDays, Long groupId, Integer page, Integer size) {
            this(scope, minDays, groupId, page, size, null);
        }

        public static Filter defaults() {
            return new Filter(Scope.ACTIVE, null, null, null, null);
        }
    }

    public record Summary(long totalDebtors, long overdue7Plus, BigDecimal totalDebt, BigDecimal closedDebt) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("totalDebtors", totalDebtors);
            m.put("overdue7Plus", overdue7Plus);
            m.put("totalDebt", totalDebt);
            m.put("closedDebt", closedDebt);
            return m;
        }
    }

    @Transactional(readOnly = true)
    public DebtorsListResponse debtors(Filter filter, LocalDate today) {
        java.util.function.Predicate<TodayStatus> todayFilter = parseToday(filter.today());
        List<DebtorsListResponse.DebtorStudent> all = collect(filter, today);
        withToday(all, today);
        if (todayFilter != null) {
            all = all.stream().filter(r -> todayFilter.test(TodayStatus.valueOf(r.getTodayStatus()))).toList();
        }
        int page = filter.page() != null ? Math.max(filter.page(), 0) : 0;
        int size = filter.size() != null ? Math.min(Math.max(filter.size(), 1), 500) : all.size();
        List<DebtorsListResponse.DebtorStudent> pageRows = all.stream()
            .skip((long) page * Math.max(size, 1))
            .limit(Math.max(size, 0))
            .toList();
        Summary summary = summarize(all, filter, today);
        return DebtorsListResponse.builder()
            .totalDebtors(summary.totalDebtors())
            .overdue7Plus(summary.overdue7Plus())
            .totalDebt(summary.totalDebt())
            .scope(filter.scope().name())
            .page(page)
            .size(size)
            .students(new ArrayList<>(pageRows))
            .build();
    }

    @Transactional(readOnly = true)
    public Summary debtorSummary(Filter filter, LocalDate today) {
        return summarize(collect(filter, today), filter, today);
    }

    /** Qarzdor o'quvchilar soni (dashboard, analitika). */
    @Transactional(readOnly = true)
    public long countDebtors(LocalDate today) {
        return debtorSummary(Filter.defaults(), today).totalDebtors();
    }

    private Summary summarize(List<DebtorsListResponse.DebtorStudent> rows, Filter filter, LocalDate today) {
        BigDecimal total = rows.stream().map(DebtorsListResponse.DebtorStudent::getDebt)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        long seven = rows.stream().filter(r -> r.getDaysOverdue() >= 7).count();
        BigDecimal closed = BigDecimal.ZERO;
        if (filter.scope() == Scope.ACTIVE) {
            for (StudentGroup sg : studentGroupRepository.findWithDebt()) {
                if (!BillingStatusService.isOpen(sg)) {
                    closed = closed.add(Money.nz(sg.getBalance()).negate());
                }
            }
        }
        return new Summary(rows.size(), seven, total, closed);
    }

    private List<DebtorsListResponse.DebtorStudent> collect(Filter filter, LocalDate today) {
        Map<Long, List<StudentGroup>> byStudent = new LinkedHashMap<>();
        for (StudentGroup sg : studentGroupRepository.findWithDebt()) {
            if (!inScope(sg, filter.scope())) {
                continue;
            }
            byStudent.computeIfAbsent(sg.getStudent().getId(), k -> new ArrayList<>()).add(sg);
        }

        List<DebtorsListResponse.DebtorStudent> rows = new ArrayList<>();
        for (List<StudentGroup> sgs : byStudent.values()) {
            List<StudentGroup> overdue = sgs.stream().filter(sg -> statusService.isOverdue(sg, today)).toList();
            if (overdue.isEmpty()) {
                continue;
            }
            if (filter.groupId() != null && sgs.stream().noneMatch(sg -> filter.groupId().equals(sg.getGroup().getId()))) {
                continue;
            }
            LocalDate debtSince = overdue.stream().map(StudentGroup::getDebtSince)
                .min(Comparator.naturalOrder()).orElseThrow();
            long days = ChronoUnit.DAYS.between(debtSince, today);
            if (filter.minDays() != null && days < filter.minDays()) {
                continue;
            }
            rows.add(toRow(sgs.get(0).getStudent(), sgs, debtSince, days, today));
        }
        rows.sort(Comparator.comparingLong(DebtorsListResponse.DebtorStudent::getDaysOverdue).reversed()
            .thenComparing(DebtorsListResponse.DebtorStudent::getStudentId));
        return rows;
    }

    private boolean inScope(StudentGroup sg, Scope scope) {
        if (scope == Scope.ALL) {
            return true;
        }
        StudentStatus st = sg.getStudent().getStatus();
        return BillingStatusService.isOpen(sg) && (st == StudentStatus.ACTIVE || st == StudentStatus.FROZEN);
    }

    private DebtorsListResponse.DebtorStudent toRow(Student s, List<StudentGroup> sgs, LocalDate debtSince,
                                                    long days, LocalDate today) {
        List<DebtorsListResponse.DebtorGroup> groups = sgs.stream()
            .sorted(Comparator.comparing(StudentGroup::getDebtSince))
            .map(sg -> DebtorsListResponse.DebtorGroup.builder()
                .studentGroupId(sg.getId())
                .groupId(sg.getGroup().getId())
                .groupName(sg.getGroup().getGroupName())
                .debt(Money.nz(sg.getBalance()).negate())
                .debtSince(sg.getDebtSince())
                .daysOverdue(ChronoUnit.DAYS.between(sg.getDebtSince(), today))
                .status(statusService.statusOf(sg.getBalance(), sg.getDebtSince(), today).name())
                .closed(!BillingStatusService.isOpen(sg))
                .build())
            .toList();
        BigDecimal debt = groups.stream().map(DebtorsListResponse.DebtorGroup::getDebt)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal monthly = Money.nz(s.getMonthlyFee());
        long monthsUnpaid = monthly.signum() > 0 ? Money.ceilDiv(debt, monthly) : 1;
        String fullName = ((s.getFirstName() != null ? s.getFirstName() : "") + " "
            + (s.getLastName() != null ? s.getLastName() : "")).trim();
        return DebtorsListResponse.DebtorStudent.builder()
            .studentId(s.getId())
            .fullName(fullName)
            .phone(s.getPhone())
            .debt(debt)
            .debtSince(debtSince)
            .daysOverdue(days)
            .status(PaymentStatus.OVERDUE.name())
            .monthlyAmount(monthly)
            .groups(new ArrayList<>(groups))
            .totalDebt(debt)
            .amount(monthly)
            .monthsUnpaid(Math.max(monthsUnpaid, 1))
            .nextPaymentDate(debtSince)
            .groupName(groups.isEmpty() ? null : groups.get(0).getGroupName())
            .build();
    }

    /**
     * Har qatorga (va guruhiga) bugungi holat va oxirgi kelgan sana — 2 so'rov (bugungi davomat, oxirgi PRESENT/LATE)
     * + guruh bo'yicha dars kuni tekshiruvi (har guruh bir marta).
     */
    private void withToday(List<DebtorsListResponse.DebtorStudent> rows, LocalDate today) {
        if (rows.isEmpty()) {
            return;
        }
        List<Long> ids = rows.stream().map(DebtorsListResponse.DebtorStudent::getStudentId).toList();
        Map<String, com.crm.entity.enums.AttendanceStatus> marked = new java.util.HashMap<>();
        for (com.crm.entity.Attendance a : attendanceRepository.findByStudentIdsAndDate(ids, today)) {
            marked.put(a.getStudent().getId() + ":" + a.getGroup().getId(), a.getStatus());
        }
        Map<String, LocalDate> last = new java.util.HashMap<>();
        for (Object[] r : attendanceRepository.findLastAttendedByStudentIds(ids, ATTENDED)) {
            last.put(r[0] + ":" + r[1], (LocalDate) r[2]);
        }
        Map<Long, Boolean> lessonToday = new java.util.HashMap<>();
        for (DebtorsListResponse.DebtorStudent row : rows) {
            TodayStatus best = null;
            LocalDate lastAll = null;
            for (DebtorsListResponse.DebtorGroup g : row.getGroups()) {
                String key = row.getStudentId() + ":" + g.getGroupId();
                com.crm.entity.enums.AttendanceStatus a = marked.get(key);
                TodayStatus st;
                if (a != null) {
                    st = TodayStatus.valueOf(a.name());
                } else if (lessonToday.computeIfAbsent(g.getGroupId(), id -> scheduleService.hasLessonOn(id, today))) {
                    st = TodayStatus.NOT_MARKED;
                } else {
                    st = TodayStatus.NO_LESSON;
                }
                g.setTodayStatus(st.name());
                g.setLastAttendedOn(last.get(key));
                best = best == null || st.ordinal() < best.ordinal() ? st : best;
                if (last.get(key) != null && (lastAll == null || last.get(key).isAfter(lastAll))) {
                    lastAll = last.get(key);
                }
            }
            row.setTodayStatus((best != null ? best : TodayStatus.NO_LESSON).name());
            // Oxirgi kelgan sana — o'quvchining barcha guruhlari bo'yicha (qarzsiz guruh ham)
            for (Map.Entry<String, LocalDate> e : last.entrySet()) {
                if (e.getKey().startsWith(row.getStudentId() + ":") && (lastAll == null || e.getValue().isAfter(lastAll))) {
                    lastAll = e.getValue();
                }
            }
            row.setLastAttendedOn(lastAll);
        }
    }

    private static java.util.function.Predicate<TodayStatus> parseToday(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (v.equals("PRESENT")) {
            return st -> st == TodayStatus.PRESENT || st == TodayStatus.LATE;     // bugun kelgan
        }
        try {
            TodayStatus exact = TodayStatus.valueOf(v);
            return st -> st == exact;
        } catch (IllegalArgumentException e) {
            throw com.crm.exception.CodedException.badRequest("debtors.today.invalid", raw);
        }
    }

    // ── Kutilayotgan to'lovlar ─────────────────────────────────────────

    /**
     * {@code GET /api/payments/expected} — R2 (billing-v2 §14.2): har hisoblanadigan yozilma uchun BITTA, sanasi
     * bugundan keyin ({@link BillingStatusService#upcoming}). Qarzdor ham keyingi davri bilan kiradi (qarz — {@code debt}).
     * Standart oraliq — ertadan bugun + 30 gacha. Guruh tugagach — yo'q (R3).
     */
    @Transactional(readOnly = true)
    public ExpectedPaymentsResponse expected(LocalDate from, LocalDate to, LocalDate today) {
        LocalDate start = from != null ? from : today.plusDays(1);
        LocalDate end = to != null ? to : today.plusDays(30);

        List<StudentGroup> candidates = studentGroupRepository.findExpectedCandidates();
        Map<Long, List<BillingCalendar.Span>> billed = new java.util.HashMap<>();
        List<Long> ids = candidates.stream().map(StudentGroup::getId).toList();
        for (int i = 0; i < ids.size(); i += 1000) {
            for (Object[] row : periodRepository.findSpansByStudentGroupIds(ids.subList(i, Math.min(ids.size(), i + 1000)))) {
                billed.computeIfAbsent((Long) row[0], k -> new ArrayList<>())
                    .add(new BillingCalendar.Span((LocalDate) row[1], (LocalDate) row[2]));
            }
        }

        Map<LocalDate, List<ExpectedPaymentsResponse.ExpectedStudent>> byDate = new TreeMap<>();
        BigDecimal total = BigDecimal.ZERO;
        java.util.Set<Long> students = new java.util.HashSet<>();
        for (StudentGroup sg : candidates) {
            BillingStatusService.Upcoming next = statusService.upcoming(sg, billed.getOrDefault(sg.getId(), List.of()), today);
            if (next == null || next.date().isBefore(start) || next.date().isAfter(end)) {
                continue;
            }
            PaymentStatus st = statusService.statusOf(sg.getBalance(), sg.getDebtSince(), today);
            Student s = sg.getStudent();
            BigDecimal amount = Money.nz(next.amount());
            BigDecimal debt = Money.nz(sg.getBalance()).signum() < 0 ? Money.nz(sg.getBalance()).negate() : BigDecimal.ZERO;
            byDate.computeIfAbsent(next.date(), k -> new ArrayList<>())
                .add(ExpectedPaymentsResponse.ExpectedStudent.builder()
                    .studentId(s.getId())
                    .fullName(((s.getFirstName() != null ? s.getFirstName() : "") + " "
                        + (s.getLastName() != null ? s.getLastName() : "")).trim())
                    .phone(s.getPhone())
                    .studentGroupId(sg.getId())
                    .groupId(sg.getGroup().getId())
                    .groupName(sg.getGroup().getGroupName())
                    .amount(amount)
                    .paymentStatus(st.name())
                    .debt(Money.normalize(debt))
                    .daysUntil(ChronoUnit.DAYS.between(today, next.date()))
                    .proratedLessons(next.proratedLessons())
                    .lessonPrice(next.lessonPrice() != null ? Money.normalize(next.lessonPrice()) : null)
                    .build());
            total = total.add(amount);
            students.add(s.getId());
        }

        List<ExpectedPaymentsResponse.DayBucket> days = new ArrayList<>();
        byDate.forEach((date, list) -> days.add(ExpectedPaymentsResponse.DayBucket.builder()
            .date(date)
            .studentCount(list.size())
            .dayTotal(list.stream().map(ExpectedPaymentsResponse.ExpectedStudent::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
            .students(list)
            .build()));
        return ExpectedPaymentsResponse.builder()
            .totalStudents(students.size())
            .totalAmount(total)
            .days(days)
            .build();
    }
}
