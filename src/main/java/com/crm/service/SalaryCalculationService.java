package com.crm.service;

import com.crm.billing.Money;
import com.crm.billing.PeriodCoverageService;
import com.crm.config.PayrollProperties;
import com.crm.dto.response.PayrollCalculationDetails;
import com.crm.dto.response.PayrollCalculationDetails.Bonus;
import com.crm.dto.response.PayrollCalculationDetails.Items;
import com.crm.dto.response.PayrollCalculationDetails.Kpi;
import com.crm.dto.response.PayrollCalculationDetails.LeaveItem;
import com.crm.dto.response.PayrollCalculationDetails.SubstitutionItem;
import com.crm.dto.response.PayrollCalculationDetails.LessonEnrollment;
import com.crm.dto.response.PayrollCalculationDetails.Line;
import com.crm.dto.response.PayrollCalculationDetails.NewStudent;
import com.crm.dto.response.PayrollCalculationDetails.PaidPeriod;
import com.crm.dto.response.PayrollCalculationDetails.RuleSnapshot;
import com.crm.dto.response.SalaryCalculationDto;
import com.crm.entity.BalanceTransaction;
import com.crm.entity.BillingMigrationRun;
import com.crm.entity.BillingPeriod;
import com.crm.entity.BonusPenalty;
import com.crm.entity.Leave;
import com.crm.entity.LessonSubstitution;
import com.crm.entity.Payment;
import com.crm.entity.SalaryRule;
import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.entity.Teacher;
import com.crm.entity.User;
import com.crm.entity.enums.BalanceTransactionType;
import com.crm.entity.enums.BillingPeriodStatus;
import com.crm.entity.enums.BonusPenaltyKind;
import com.crm.entity.enums.BonusPenaltyStatus;
import com.crm.entity.enums.BonusTargetType;
import com.crm.entity.enums.LeaveStatus;
import com.crm.entity.enums.SubstitutionStatus;
import com.crm.entity.enums.PaymentStatus;
import com.crm.entity.enums.UserRole;
import com.crm.exception.BadRequestException;
import com.crm.exception.CodedException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.BonusPenaltyRepository;
import com.crm.repository.LeaveRepository;
import com.crm.repository.LessonSubstitutionRepository;
import com.crm.repository.SalaryRuleRepository;
import com.crm.repository.TeacherRepository;
import com.crm.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Oylik hisobi — docs/design/payroll-v2.md §2–§3, §6, §11 qarorlari.
 *
 * <p><b>Deterministik:</b> faqat tarixiy ma'lumot — {@code billing_periods.teacher_id/paid_on},
 * {@code LESSON_CHARGE.teacher_id}, ledger kreditlari, to'lov sanasi, {@code attributed_user_id},
 * bonus sanasi, APPROVED ta'til ({@code LEAVE_DEDUCTION}) va CONDUCTED o'rinbosar darslari
 * ({@code SUBSTITUTE_LESSONS}, leaves-exams-contracts §3). Hozirgi {@code group.teacher}, {@code student.status}
 * va "bugun" ishlatilmaydi.
 *
 * <p>Hech narsa yozmaydi: natija {@link SalaryCalculationDto} + {@link PayrollCalculationDetails}.
 */
@Service
@RequiredArgsConstructor
public class SalaryCalculationService {

    /** "To'lagan" sanaladigan davr holatlari (yig'iladigan majburiyat, §2.1). */
    static final Set<BillingPeriodStatus> COLLECTIBLE =
        Set.of(BillingPeriodStatus.CHARGED, BillingPeriodStatus.PARTIALLY_REFUNDED);

    /** KPI: oy oxirida hisob davri bor o'quvchi (§2.3) — migratsiya davrlari ham. */
    static final Set<BillingPeriodStatus> ACTIVE_PERIOD = Set.of(
        BillingPeriodStatus.CHARGED, BillingPeriodStatus.PARTIALLY_REFUNDED,
        BillingPeriodStatus.MIGRATED, BillingPeriodStatus.PREPAID_LEGACY);

    private static final Set<BillingMigrationRun.Status> APPLIED_RUNS =
        Set.of(BillingMigrationRun.Status.APPLIED, BillingMigrationRun.Status.APPLIED_WITH_ERRORS);

    private final UserRepository userRepository;
    private final TeacherRepository teacherRepository;
    private final SalaryRuleRepository salaryRuleRepository;
    private final BonusPenaltyRepository bonusPenaltyRepository;
    private final PeriodCoverageService coverageService;
    private final PayrollProperties properties;
    private final EntityManager em;
    private final ObjectMapper objectMapper;
    private final LeaveRepository leaveRepository;
    private final LessonSubstitutionRepository substitutionRepository;
    private final WorkdayCalendar workdayCalendar;

    @Transactional(readOnly = true)
    public List<SalaryCalculationDto> calculateAll(int month, int year) {
        validatePeriod(month, year);
        requireNotBeforeCutover(month, year);
        LocalDate asOf = YearMonth.of(year, month).atEndOfMonth();
        List<SalaryCalculationDto> out = new ArrayList<>();
        List<User> users = new ArrayList<>(userRepository.findByIsActiveTrue());
        users.sort(Comparator.comparing(User::getId));
        for (User user : users) {
            if (user.getRole() == null || !SalaryRuleService.SALARY_ROLES.contains(user.getRole())) {
                continue;
            }
            // Qoidasiz ADMIN ro'yxatga tushmaydi (ko'pchilik admin oylik olmaydi);
            // qoidasiz TEACHER/SALES — haqiqiy muammo, "Oylik qoidasi topilmadi" bo'lib ko'rinadi.
            if (user.getRole() == UserRole.ADMIN && salaryRuleRepository.resolveRule(user, asOf).isEmpty()) {
                continue;
            }
            out.add(calculate(user, month, year));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public SalaryCalculationDto calculateForUser(Long userId, int month, int year) {
        validatePeriod(month, year);
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        return calculate(user, month, year);
    }

    /** DRAFT / preview: xodimning PENDING bonuslari "kutilmoqda" sifatida qo'shiladi (§4). */
    @Transactional(readOnly = true)
    public SalaryCalculationDto calculate(User user, int month, int year) {
        requireNotBeforeCutover(month, year);
        YearMonth ym = YearMonth.of(year, month);
        LocalDate from = ym.atDay(1);
        LocalDate to = ym.atEndOfMonth();

        Optional<SalaryRule> ruleOpt = salaryRuleRepository.resolveRule(user, to);
        if (ruleOpt.isEmpty()) {
            return notCalculable(user, month, year, "RULE_NOT_FOUND", "Oylik qoidasi topilmadi");
        }
        SalaryRule rule = ruleOpt.get();
        UserRole role = user.getRole();
        if (role == UserRole.TEACHER) {
            Teacher teacher = teacherRepository.findByUser_Id(user.getId()).orElse(null);
            if (teacher == null) {
                return teacherProfileProblem(user, month, year);
            }
            return teacher(user, teacher, rule, month, year, from, to);
        }
        if (role == UserRole.ADMIN || role == UserRole.SALES_MANAGER || role == UserRole.SALES_HEAD) {
            return staff(user, rule, month, year, from, to);
        }
        return notCalculable(user, month, year, "ROLE_NOT_CALCULATED", "Bu rol uchun oylik hisoblanmaydi");
    }

    // ── cutover (§11 #1) ────────────────────────────────────────────────

    /** Billing v2 cutover oyi: sozlama, aks holda qo'llangan migratsiyaning eng erta sanasi. */
    @Transactional(readOnly = true)
    public Optional<YearMonth> cutoverMonth() {
        if (properties.getCutoverDate() != null) {
            return Optional.of(YearMonth.from(properties.getCutoverDate()));
        }
        List<LocalDate> dates = em.createQuery(
                "SELECT MIN(r.cutoverDate) FROM BillingMigrationRun r WHERE r.status IN :st", LocalDate.class)
            .setParameter("st", APPLIED_RUNS).getResultList();
        return dates.isEmpty() || dates.get(0) == null ? Optional.empty() : Optional.of(YearMonth.from(dates.get(0)));
    }

    private void requireNotBeforeCutover(int month, int year) {
        Optional<YearMonth> cutover = cutoverMonth();
        if (cutover.isPresent() && YearMonth.of(year, month).isBefore(cutover.get())) {
            throw CodedException.badRequest("payroll.beforeCutover", month + "/" + year,
                cutover.get().getMonthValue() + "/" + cutover.get().getYear());
        }
    }

    private boolean estimated(int month, int year) {
        return cutoverMonth().map(c -> c.equals(YearMonth.of(year, month))).orElse(false);
    }

    // ── bonuslar (§4, §11 #5) ───────────────────────────────────────────

    /**
     * Xodimning shu sanagacha kuchga kirgan PENDING bonus/jarimalari (id o'sishida):
     * TEACHER — o'qituvchi profili bo'yicha ({@code TEACHER}), ADMIN/SALES — {@code STAFF} (userId).
     */
    @Transactional(readOnly = true)
    public List<BonusPenalty> pendingBonuses(User user, LocalDate cutoff) {
        List<BonusPenalty> candidates;
        if (user.getRole() == UserRole.TEACHER) {
            candidates = teacherRepository.findByUser_Id(user.getId())
                .map(t -> bonusPenaltyRepository.findByTeacherIdAndStatus(t.getId(), BonusPenaltyStatus.PENDING).stream()
                    .filter(b -> b.getTargetType() == BonusTargetType.TEACHER).toList())
                .orElse(List.of());
        } else {
            candidates = bonusPenaltyRepository.findByUser_IdAndStatus(user.getId(), BonusPenaltyStatus.PENDING).stream()
                .filter(b -> b.getTargetType() == BonusTargetType.STAFF).toList();
        }
        return candidates.stream()
            .filter(b -> b.getEffectiveDate() == null || !b.getEffectiveDate().isAfter(cutoff))
            .sorted(Comparator.comparing(BonusPenalty::getId))
            .toList();
    }

    /** Bonus shu xodimning oyligiga tegishlimi (APPROVE da qulf ostida qayta tekshiruv). */
    public boolean belongsTo(BonusPenalty b, User user, Teacher teacher) {
        if (b.getTargetType() == BonusTargetType.TEACHER) {
            return teacher != null && b.getTeacher() != null && teacher.getId().equals(b.getTeacher().getId());
        }
        return b.getTargetType() == BonusTargetType.STAFF
            && b.getUser() != null && user != null && user.getId().equals(b.getUser().getId());
    }

    /**
     * Snapshot dagi bonus qatorlarini berilgan ro'yxat bilan almashtiradi (gross o'zgarmaydi) —
     * APPROVE paytida qulf ostida qayta o'qilgan bonuslar uchun.
     */
    public static PayrollCalculationDetails withBonuses(
            PayrollCalculationDetails base, List<BonusPenalty> bonuses, String status) {
        List<Line> lines = new ArrayList<>();
        for (Line l : base.lines()) {
            if (!PayrollCalculationDetails.BONUS.equals(l.code()) && !PayrollCalculationDetails.PENALTY.equals(l.code())) {
                lines.add(l);
            }
        }
        BigDecimal bp = addBonusLines(lines, bonuses, status);
        Items old = base.items();
        Items items = new Items(old.paidPeriods(), old.lessonEnrollments(), old.newStudents(), old.kpi(),
            toBonusItems(bonuses, status), old.leaves(), old.substitutions());
        return new PayrollCalculationDetails(base.version(), base.month(), base.year(), base.role(), base.estimated(),
            base.rule(), lines, base.gross(), bp, base.gross().add(bp), items);
    }

    public String toJson(PayrollCalculationDetails details) {
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("calculationDetails JSON", e);
        }
    }

    public PayrollCalculationDetails fromJson(String json) {
        try {
            return objectMapper.readValue(json, PayrollCalculationDetails.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("calculationDetails JSON (v2 kutilgan)", e);
        }
    }

    // ── TEACHER (§2.1, §11 #2, #7) ──────────────────────────────────────

    private SalaryCalculationDto teacher(User user, Teacher teacher, SalaryRule rule,
                                         int month, int year, LocalDate from, LocalDate to) {
        List<PaidPeriod> paid = paidPeriods(teacher.getId(), from, to);
        List<LessonEnrollment> lessons = lessonEnrollments(teacher.getId(), from, to);
        // Birliklar: har davr 1 + har PER_LESSON yozilma ulushi (aniq kasr, ko'rsatish 4 xona)
        BigDecimal unitsExact = BigDecimal.valueOf(paid.size());
        for (LessonEnrollment l : lessons) {
            unitsExact = unitsExact.add(Money.divide(BigDecimal.valueOf(l.lessons()), BigDecimal.valueOf(l.totalLessons())));
        }
        BigDecimal units = displayUnits(unitsExact);

        BigDecimal fixed = nz(rule.getFixedSalary());
        BigDecimal perPaying = nz(rule.getPerPayingStudent());
        BigDecimal perStudentAmount = Money.uzs(perPaying.multiply(unitsExact));

        // O'rinbosar darslari (leaves-exams-contracts §3.2): stavka yo'q bo'lsa jimgina 0 emas — hisoblanmaydi
        List<LessonSubstitution> conducted = substitutionRepository.findBySubstituteAndStatus(
            teacher.getId(), SubstitutionStatus.CONDUCTED, from, to);
        BigDecimal substituteRate = substituteRate(rule, user, to);
        if (!conducted.isEmpty() && substituteRate == null) {
            return notCalculable(user, month, year, "SUBSTITUTE_RATE_MISSING",
                "O'rinbosar dars stavkasi belgilanmagan (oylik qoidasi: substituteLessonRate) — "
                    + conducted.size() + " ta o'tilgan dars");
        }
        BigDecimal substituteAmount = conducted.isEmpty() ? BigDecimal.ZERO
            : Money.uzs(substituteRate.multiply(BigDecimal.valueOf(conducted.size())));
        LeaveCalc leave = leaveDeduction(user, fixed, from, to);
        BigDecimal gross = fixed.add(perStudentAmount).subtract(leave.amount()).add(substituteAmount);

        List<Line> lines = new ArrayList<>();
        lines.add(new Line(PayrollCalculationDetails.FIXED, "Belgilangan oylik", fixed, BigDecimal.ONE, fixed, null));
        lines.add(new Line(PayrollCalculationDetails.PER_PAYING_STUDENT, "To'lagan o'quvchi",
            perPaying, units, perStudentAmount, null));
        if (leave.line() != null) {
            lines.add(leave.line());
        }
        if (!conducted.isEmpty()) {
            lines.add(new Line(PayrollCalculationDetails.SUBSTITUTE_LESSONS, "O'rinbosar darslari",
                substituteRate, BigDecimal.valueOf(conducted.size()), substituteAmount, null));
        }
        List<BonusPenalty> bonuses = pendingBonuses(user, to);
        String status = BonusPenaltyStatus.PENDING.name();
        BigDecimal bp = addBonusLines(lines, bonuses, status);

        PayrollCalculationDetails details = new PayrollCalculationDetails(
            PayrollCalculationDetails.VERSION, month, year, UserRole.TEACHER.name(), estimated(month, year),
            snapshot(rule, substituteRate), lines, gross, bp, gross.add(bp),
            new Items(paid, lessons, List.of(), null, toBonusItems(bonuses, status), leave.items(),
                toSubstitutionItems(conducted)));

        return SalaryCalculationDto.builder()
            .userId(user.getId()).fullName(fullName(user)).role(UserRole.TEACHER.name())
            .month(month).year(year)
            .baseSalary(fixed)
            .paidStudentCount(paid.size() + lessons.size())
            .paidStudentUnits(units)
            .perStudentAmount(perStudentAmount)
            .newStudentCount(0).newStudentAmount(BigDecimal.ZERO)
            .kpiApplied(false).kpiAmount(BigDecimal.ZERO)
            .leaveDeduction(leave.amount())
            .unpaidLeaveDays(leave.unpaidDays())
            .substituteLessonCount(conducted.size())
            .substituteAmount(substituteAmount)
            .grossAmount(gross)
            .bonusPenaltyAdjustment(bp)
            .totalAmount(details.net())
            .calculable(true)
            .calculationDetails(details)
            .build();
    }

    // ── Ta'til va o'rinbosar (leaves-exams-contracts §3) ────────────────

    /** {@code amount} — musbat ayirma (qatorda manfiy); {@code line} — haqsiz ish kuni bo'lmasa null. */
    private record LeaveCalc(Line line, BigDecimal amount, int unpaidDays, List<LeaveItem> items) {
    }

    /**
     * §3.1 (buyurtmachi qarori): {@code LEAVE_DEDUCTION = −uzs(fixed × haqsiz ish kunlari / oydagi ish kunlari)},
     * ish kuni — Du–Sha, bayram emas. Faqat belgilangan qismdan, {@code |ayirma| ≤ fixed} (butun oy — aynan fixed).
     * Manba — APPROVED ta'til (bekor qilish APPROVED/PAID oylikda 409 — determinizm). Haqli ta'til faqat
     * {@code items.leaves} da ko'rinadi.
     */
    private LeaveCalc leaveDeduction(User user, BigDecimal fixed, LocalDate from, LocalDate to) {
        List<Leave> leaves = leaveRepository.findOverlapping(user.getId(), from, to, Set.of(LeaveStatus.APPROVED), -1L);
        if (leaves.isEmpty()) {
            return new LeaveCalc(null, BigDecimal.ZERO, 0, List.of());
        }
        Set<LocalDate> holidays = workdayCalendar.holidays(from, to);
        int monthWorkdays = WorkdayCalendar.count(from, to, holidays);
        int unpaid = 0;
        List<LeaveItem> items = new ArrayList<>();
        for (Leave l : leaves) {
            LocalDate f = l.getFromDate().isBefore(from) ? from : l.getFromDate();
            LocalDate t = l.getToDate().isAfter(to) ? to : l.getToDate();
            int workdays = WorkdayCalendar.count(f, t, holidays);
            boolean paid = !Boolean.FALSE.equals(l.getPaid());
            items.add(new LeaveItem(l.getId(), l.getFromDate(), l.getToDate(), l.getLeaveType().name(), paid, workdays));
            if (!paid) {
                unpaid += workdays;
            }
        }
        if (unpaid == 0 || monthWorkdays == 0) {
            return new LeaveCalc(null, BigDecimal.ZERO, 0, items);
        }
        BigDecimal amount = unpaid >= monthWorkdays ? fixed : Money.proportion(fixed, unpaid, monthWorkdays).min(fixed);
        BigDecimal dailyRate = Money.uzs(Money.divide(fixed, BigDecimal.valueOf(monthWorkdays)));
        Line line = new Line(PayrollCalculationDetails.LEAVE_DEDUCTION, "Haqsiz ta'til", dailyRate,
            BigDecimal.valueOf(unpaid), amount.negate(), null);
        return new LeaveCalc(line, amount, unpaid, items);
    }

    /** O'rinbosar dars stavkasi: shaxsiy qoida → rol qoidasi (§3.3); ikkalasida ham yo'q — null. */
    private BigDecimal substituteRate(SalaryRule rule, User user, LocalDate asOf) {
        if (rule.getSubstituteLessonRate() != null) {
            return rule.getSubstituteLessonRate();
        }
        if (rule.getUser() != null && user.getRole() != null) {
            return salaryRuleRepository.findActiveRoleRules(user.getRole(), asOf).stream()
                .map(SalaryRule::getSubstituteLessonRate)
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
        }
        return null;
    }

    private static List<SubstitutionItem> toSubstitutionItems(List<LessonSubstitution> conducted) {
        return conducted.stream()
            .map(s -> new SubstitutionItem(s.getId(), s.getGroup().getId(), s.getGroup().getGroupName(),
                s.getLessonDate(), s.getOriginalTeacher().getId(),
                LessonSubstitutionService.teacherName(s.getOriginalTeacher()), s.getConductedAt()))
            .toList();
    }

    /**
     * MONTHLY: o'qituvchiga yozilgan, FIFO bo'yicha yopilgan davrlar; davr
     * {@code max(paidOn, periodStart)} oyida sanaladi. §11 #7: yopilishida real PAYMENT qatnashmagan
     * (faqat DISCOUNT/BONUS) davr sanalmaydi ({@code app.payroll.count-discount-covered=false}).
     */
    private List<PaidPeriod> paidPeriods(Long teacherId, LocalDate from, LocalDate to) {
        List<BillingPeriod> candidates = em.createQuery("""
                SELECT bp FROM BillingPeriod bp
                WHERE bp.teacherId = :t AND bp.paidOn IS NOT NULL AND bp.chargeTxId IS NOT NULL
                  AND bp.status IN :st
                  AND ((bp.paidOn BETWEEN :f AND :to) OR (bp.periodStart BETWEEN :f AND :to))
                ORDER BY bp.periodStart, bp.id
                """, BillingPeriod.class)
            .setParameter("t", teacherId).setParameter("st", COLLECTIBLE)
            .setParameter("f", from).setParameter("to", to)
            .getResultList();
        PaymentCoverage coverage = new PaymentCoverage();
        List<BillingPeriod> counted = candidates.stream()
            .filter(SalaryCalculationService::collectible)
            .filter(bp -> within(countedOn(bp), from, to))
            .filter(coverage::paid)
            .toList();
        Map<Long, StudentGroup> sgs = enrollments(counted.stream().map(BillingPeriod::getStudentGroupId).toList());
        List<PaidPeriod> out = new ArrayList<>();
        for (BillingPeriod bp : counted) {
            StudentGroup sg = sgs.get(bp.getStudentGroupId());
            out.add(new PaidPeriod(bp.getId(), bp.getStudentGroupId(),
                sg != null ? sg.getStudent().getId() : null, sg != null ? studentName(sg.getStudent()) : null,
                sg != null ? sg.getGroup().getId() : null, sg != null ? sg.getGroup().getGroupName() : null,
                bp.getPeriodStart(), bp.getPaidOn(), countedOn(bp)));
        }
        return out;
    }

    /**
     * PER_LESSON: oyda o'qituvchining net LESSON_CHARGE i bor yozilmalar. Ulush = o'qituvchining
     * darslari / yozilmaning oydagi barcha darslari (§11 #2) — o'qituvchi almashmasa 1.
     */
    private List<LessonEnrollment> lessonEnrollments(Long teacherId, LocalDate from, LocalDate to) {
        Map<Long, Integer> mine = new LinkedHashMap<>();
        for (BalanceTransaction c : netLessonCharges(em.createQuery("""
                SELECT t FROM BalanceTransaction t
                WHERE t.type = :lc AND t.teacherId = :t AND t.effectiveDate BETWEEN :f AND :to
                ORDER BY t.id
                """, BalanceTransaction.class)
            .setParameter("lc", BalanceTransactionType.LESSON_CHARGE).setParameter("t", teacherId)
            .setParameter("f", from).setParameter("to", to)
            .getResultList())) {
            mine.merge(c.getStudentGroup().getId(), 1, Integer::sum);
        }
        if (mine.isEmpty()) {
            return List.of();
        }
        Map<Long, Integer> total = new HashMap<>();
        for (BalanceTransaction c : netLessonCharges(em.createQuery("""
                SELECT t FROM BalanceTransaction t
                WHERE t.type = :lc AND t.studentGroup.id IN :sgs AND t.effectiveDate BETWEEN :f AND :to
                """, BalanceTransaction.class)
            .setParameter("lc", BalanceTransactionType.LESSON_CHARGE).setParameter("sgs", mine.keySet())
            .setParameter("f", from).setParameter("to", to)
            .getResultList())) {
            total.merge(c.getStudentGroup().getId(), 1, Integer::sum);
        }
        Map<Long, StudentGroup> sgs = enrollments(mine.keySet());
        List<LessonEnrollment> out = new ArrayList<>();
        mine.forEach((sgId, n) -> {
            StudentGroup sg = sgs.get(sgId);
            int all = Math.max(total.getOrDefault(sgId, n), n);
            BigDecimal share = displayUnits(Money.divide(BigDecimal.valueOf(n), BigDecimal.valueOf(all)));
            out.add(new LessonEnrollment(sgId, sg.getStudent().getId(), studentName(sg.getStudent()),
                sg.getGroup().getId(), sg.getGroup().getGroupName(), n, all, share));
        });
        return out;
    }

    /** Teskari yozuvlari (LESSON_REFUND, REVERSAL) bilan net manfiy qolgan darslar. */
    private List<BalanceTransaction> netLessonCharges(List<BalanceTransaction> charges) {
        Map<Long, BigDecimal> related = relatedSums(charges.stream().map(BalanceTransaction::getId).toList());
        return charges.stream()
            .filter(c -> c.getAmount().add(related.getOrDefault(c.getId(), BigDecimal.ZERO)).signum() < 0)
            .toList();
    }

    // ── ADMIN / SALES_MANAGER (§2.2, §2.3, §11 #5) ──────────────────────

    private SalaryCalculationDto staff(User user, SalaryRule rule, int month, int year,
                                       LocalDate from, LocalDate to) {
        boolean admin = user.getRole() == UserRole.ADMIN;
        List<NewStudent> newStudents = newStudents(user.getId(), from, to);
        BigDecimal fixed = nz(rule.getFixedSalary());
        BigDecimal perNew = nz(rule.getPerNewStudent());
        BigDecimal newAmount = perNew.multiply(BigDecimal.valueOf(newStudents.size()));

        List<Line> lines = new ArrayList<>();
        lines.add(new Line(PayrollCalculationDetails.FIXED, "Belgilangan oylik", fixed, BigDecimal.ONE, fixed, null));
        lines.add(new Line(PayrollCalculationDetails.PER_NEW_STUDENT, "Yangi o'quvchi",
            perNew, BigDecimal.valueOf(newStudents.size()), newAmount, null));

        Kpi kpi = null;
        BigDecimal kpiAmount = BigDecimal.ZERO;
        Integer active = null;
        if (admin) {
            long actual = activeAtMonthEnd(from, to);
            boolean applied = rule.getKpiThreshold() != null && actual >= rule.getKpiThreshold();
            kpiAmount = applied ? nz(rule.getKpiBonus()) : BigDecimal.ZERO;
            kpi = new Kpi(rule.getKpiThreshold(), actual, applied);
            active = (int) actual;
            lines.add(new Line(PayrollCalculationDetails.KPI, "KPI bonusi", nz(rule.getKpiBonus()),
                applied ? BigDecimal.ONE : BigDecimal.ZERO, kpiAmount, null));
        }
        LeaveCalc leave = leaveDeduction(user, fixed, from, to);
        if (leave.line() != null) {
            lines.add(leave.line());
        }
        BigDecimal gross = fixed.add(newAmount).add(kpiAmount).subtract(leave.amount());
        List<BonusPenalty> bonuses = pendingBonuses(user, to);
        String status = BonusPenaltyStatus.PENDING.name();
        BigDecimal bp = addBonusLines(lines, bonuses, status);

        PayrollCalculationDetails details = new PayrollCalculationDetails(
            PayrollCalculationDetails.VERSION, month, year, user.getRole().name(), estimated(month, year),
            snapshot(rule, null), lines, gross, bp, gross.add(bp),
            new Items(List.of(), List.of(), newStudents, kpi, toBonusItems(bonuses, status), leave.items(), List.of()));

        return SalaryCalculationDto.builder()
            .userId(user.getId()).fullName(fullName(user)).role(user.getRole().name())
            .month(month).year(year)
            .baseSalary(fixed)
            .newStudentCount(newStudents.size()).newStudentAmount(newAmount)
            .kpiApplied(kpi != null && kpi.applied()).kpiAmount(kpiAmount)
            .leaveDeduction(leave.amount())
            .unpaidLeaveDays(leave.unpaidDays())
            .totalActiveStudents(active)
            .grossAmount(gross)
            .bonusPenaltyAdjustment(bp)
            .totalAmount(details.net())
            .calculable(true)
            .calculationDetails(details)
            .build();
    }

    /**
     * Yangi o'quvchi: birinchi "to'langan" davri ({@code (periodStart, id)} bo'yicha; §11 #7 — real
     * PAYMENT qatnashgan) shu oyda sanaladi; yig'iladigan MONTHLY davri umuman yo'q bo'lsa — birinchi
     * PAID, naqdli to'lov sanasi shu oyda.
     */
    private List<NewStudent> newStudents(Long userId, LocalDate from, LocalDate to) {
        List<Student> students = em.createQuery(
                "SELECT s FROM Student s WHERE s.attributedUserId = :u ORDER BY s.id", Student.class)
            .setParameter("u", userId).getResultList();
        if (students.isEmpty()) {
            return List.of();
        }
        List<Long> ids = students.stream().map(Student::getId).toList();
        PaymentCoverage coverage = new PaymentCoverage();
        Map<Long, BillingPeriod> firstPaid = new HashMap<>();
        Set<Long> hasPeriods = new TreeSet<>();
        for (Object[] row : em.createQuery("""
                SELECT bp, sg.student.id FROM BillingPeriod bp, StudentGroup sg
                WHERE sg.id = bp.studentGroupId AND sg.student.id IN :ids
                  AND bp.chargeTxId IS NOT NULL AND bp.status IN :st
                """, Object[].class)
            .setParameter("ids", ids).setParameter("st", COLLECTIBLE).getResultList()) {
            BillingPeriod bp = (BillingPeriod) row[0];
            if (!collectible(bp)) {
                continue;
            }
            Long studentId = (Long) row[1];
            hasPeriods.add(studentId);
            if (bp.getPaidOn() == null || !coverage.paid(bp)) {
                continue;
            }
            firstPaid.merge(studentId, bp, (a, b) -> PERIOD_ORDER.compare(a, b) <= 0 ? a : b);
        }
        List<Long> withoutPeriods = ids.stream().filter(id -> !hasPeriods.contains(id)).toList();
        Map<Long, Payment> firstPayment = new HashMap<>();
        if (!withoutPeriods.isEmpty()) {
            for (Payment p : em.createQuery("""
                    SELECT p FROM Payment p
                    WHERE p.student.id IN :ids AND p.status = :paid AND COALESCE(p.cashAmount, p.amount) > 0
                    ORDER BY p.paymentDate, p.id
                    """, Payment.class)
                .setParameter("ids", withoutPeriods).setParameter("paid", PaymentStatus.PAID).getResultList()) {
                firstPayment.putIfAbsent(p.getStudent().getId(), p);
            }
        }

        List<NewStudent> out = new ArrayList<>();
        for (Student s : students) {
            if (hasPeriods.contains(s.getId())) {
                BillingPeriod bp = firstPaid.get(s.getId());
                if (bp != null && within(countedOn(bp), from, to)) {
                    out.add(new NewStudent(s.getId(), studentName(s), countedOn(bp), "PERIOD"));
                }
                continue;
            }
            Payment p = firstPayment.get(s.getId());
            if (p != null && within(p.getPaymentDate(), from, to)) {
                out.add(new NewStudent(s.getId(), studentName(s), p.getPaymentDate(), "PAYMENT"));
            }
        }
        return out;
    }

    /** Oy oxirida hisob davri bor + oyda net LESSON_CHARGE i bor o'quvchilar (DISTINCT). */
    private long activeAtMonthEnd(LocalDate from, LocalDate to) {
        Set<Long> ids = new TreeSet<>(em.createQuery("""
                SELECT DISTINCT sg.student.id FROM BillingPeriod bp, StudentGroup sg
                WHERE sg.id = bp.studentGroupId AND bp.periodStart <= :to AND bp.periodEnd >= :to
                  AND bp.status IN :st
                """, Long.class)
            .setParameter("to", to).setParameter("st", ACTIVE_PERIOD).getResultList());
        for (BalanceTransaction c : netLessonCharges(em.createQuery("""
                SELECT t FROM BalanceTransaction t
                WHERE t.type = :lc AND t.effectiveDate BETWEEN :f AND :to
                """, BalanceTransaction.class)
            .setParameter("lc", BalanceTransactionType.LESSON_CHARGE).setParameter("f", from).setParameter("to", to)
            .getResultList())) {
            ids.add(c.getStudent().getId());
        }
        return ids.size();
    }

    // ── yordamchilar ────────────────────────────────────────────────────

    private static final Comparator<BillingPeriod> PERIOD_ORDER = Comparator
        .comparing(BillingPeriod::getPeriodStart).thenComparing(BillingPeriod::getId);

    /**
     * §11 #7: davr real PAYMENT bilan yopilganmi — SG ledgeri bir marta o'ynaladi (hisob ichida kesh).
     * {@code count-discount-covered=true} bo'lsa har qanday kredit bilan yopilgani ham sanaladi.
     */
    private final class PaymentCoverage {
        private final Map<Long, Set<Long>> bySg = new HashMap<>();

        boolean paid(BillingPeriod bp) {
            if (properties.isCountDiscountCovered()) {
                return true;
            }
            return bySg.computeIfAbsent(bp.getStudentGroupId(), coverageService::paymentCoveredCharges)
                .contains(bp.getChargeTxId());
        }
    }

    /** Davr qaysi kuni "to'langan" sanaladi: {@code max(paidOn, periodStart)} (§2.1). */
    static LocalDate countedOn(BillingPeriod bp) {
        return bp.getPaidOn().isBefore(bp.getPeriodStart()) ? bp.getPeriodStart() : bp.getPaidOn();
    }

    /** Yozilgan, qaytarilmagan qismi bor davr (PeriodCoverageService.isCollectible bilan bir xil). */
    static boolean collectible(BillingPeriod bp) {
        return COLLECTIBLE.contains(bp.getStatus()) && bp.getChargeTxId() != null
            && nz(bp.getAmount()).subtract(nz(bp.getRefundedAmount())).signum() > 0;
    }

    /** Birlik soni ko'rinishi: butun bo'lsa scale 0, aks holda 4 xona (JSON da ilmiy yozuvsiz). */
    static BigDecimal displayUnits(BigDecimal exact) {
        BigDecimal v = exact.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
        return v.scale() < 0 ? v.setScale(0) : v;
    }

    /** Asl yozuvga bog'langan teskari yozuvlar yig'indisi (LESSON_REFUND, REVERSAL). */
    private Map<Long, BigDecimal> relatedSums(Collection<Long> ids) {
        Map<Long, BigDecimal> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        for (Object[] row : em.createQuery("""
                SELECT r.relatedTxId, SUM(r.amount) FROM BalanceTransaction r
                WHERE r.relatedTxId IN :ids GROUP BY r.relatedTxId
                """, Object[].class).setParameter("ids", ids).getResultList()) {
            out.put((Long) row[0], (BigDecimal) row[1]);
        }
        return out;
    }

    private Map<Long, StudentGroup> enrollments(Collection<Long> ids) {
        Map<Long, StudentGroup> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        em.createQuery("""
                SELECT sg FROM StudentGroup sg JOIN FETCH sg.student JOIN FETCH sg.group
                WHERE sg.id IN :ids
                """, StudentGroup.class)
            .setParameter("ids", Set.copyOf(ids)).getResultList().forEach(sg -> out.put(sg.getId(), sg));
        return out;
    }

    private static BigDecimal addBonusLines(List<Line> lines, List<BonusPenalty> bonuses, String status) {
        BigDecimal bonus = BigDecimal.ZERO;
        BigDecimal penalty = BigDecimal.ZERO;
        int nb = 0;
        int np = 0;
        for (BonusPenalty b : bonuses) {
            if (b.getKind() == BonusPenaltyKind.BONUS) {
                bonus = bonus.add(b.getAmount());
                nb++;
            } else {
                penalty = penalty.add(b.getAmount());
                np++;
            }
        }
        if (nb > 0) {
            lines.add(new Line(PayrollCalculationDetails.BONUS, "Bonuslar", null, BigDecimal.valueOf(nb), bonus, status));
        }
        if (np > 0) {
            lines.add(new Line(PayrollCalculationDetails.PENALTY, "Jarimalar", null, BigDecimal.valueOf(np),
                penalty.negate(), status));
        }
        return bonus.subtract(penalty);
    }

    private static List<Bonus> toBonusItems(List<BonusPenalty> bonuses, String status) {
        return bonuses.stream()
            .map(b -> new Bonus(b.getId(), b.getKind().name(), b.getAmount(), b.getEffectiveDate(),
                b.getReason(), status))
            .toList();
    }

    /** {@code substituteRate} — hisobda ishlatilgan (shaxsiy yoki rol qoidasidan) o'rinbosar stavkasi. */
    private static RuleSnapshot snapshot(SalaryRule r, BigDecimal substituteRate) {
        return new RuleSnapshot(r.getId(), r.getUser() != null ? "PERSONAL" : "ROLE",
            r.getRole() != null ? r.getRole().name() : null, r.getEffectiveFrom(), r.getEffectiveTo(),
            nz(r.getFixedSalary()), nz(r.getPerPayingStudent()), nz(r.getPerNewStudent()),
            r.getKpiThreshold(), nz(r.getKpiBonus()), substituteRate);
    }

    /**
     * TEACHER userga {@code teachers.user_id} bo'yicha profil yo'q — sababni ajratadi (payroll-v2 §12):
     * shu telefon/email li profil BOSHQA userga bog'langanmi yoki profil umuman yo'qmi (egasiz mos profil
     * bo'lsa — repair bilan bog'lash maslahati).
     */
    private SalaryCalculationDto teacherProfileProblem(User user, int month, int year) {
        List<Teacher> candidates = new ArrayList<>();
        if (user.getPhone() != null && !user.getPhone().isBlank()) {
            candidates.addAll(teacherRepository.findAllByPhone(user.getPhone().trim()));
        }
        if (user.getEmail() != null && !user.getEmail().isBlank()) {
            candidates.addAll(teacherRepository.findAllByEmailIgnoreCase(user.getEmail().trim()));
        }
        Optional<Teacher> other = candidates.stream()
            .filter(t -> t.getUser() != null && !t.getUser().getId().equals(user.getId())).findFirst();
        if (other.isPresent()) {
            return notCalculable(user, month, year, "TEACHER_PROFILE_LINKED_TO_OTHER_USER",
                "O'qituvchi profili boshqa userga bog'langan (teacher #" + other.get().getId()
                    + " → user #" + other.get().getUser().getId() + ")");
        }
        Optional<Teacher> orphan = candidates.stream().filter(t -> t.getUser() == null).findFirst();
        return notCalculable(user, month, year, "TEACHER_PROFILE_MISSING", orphan
            .map(t -> "O'qituvchi profili yo'q — egasiz mos profil #" + t.getId()
                + " bor, /api/admin/repair/link-teacher-users bilan bog'lang")
            .orElse("O'qituvchi profili yo'q — /api/admin/repair/link-teacher-users profil yaratadi"));
    }

    private static SalaryCalculationDto notCalculable(User user, int month, int year, String code, String message) {
        return SalaryCalculationDto.builder()
            .userId(user.getId())
            .fullName(fullName(user))
            .role(user.getRole() != null ? user.getRole().name() : null)
            .month(month)
            .year(year)
            .calculable(false)
            .message(message)
            .messageCode(code)
            .build();
    }

    private static boolean within(LocalDate d, LocalDate from, LocalDate to) {
        return d != null && !d.isBefore(from) && !d.isAfter(to);
    }

    static void validatePeriod(int month, int year) {
        if (month < 1 || month > 12) {
            throw new BadRequestException("Oy 1–12 oralig'ida bo'lishi kerak");
        }
        if (year < 2000 || year > 2100) {
            throw new BadRequestException("Noto'g'ri yil");
        }
    }

    private static String studentName(Student s) {
        return ((s.getFirstName() != null ? s.getFirstName() : "")
            + " " + (s.getLastName() != null ? s.getLastName() : "")).trim();
    }

    static String fullName(User u) {
        return ((u.getFirstName() != null ? u.getFirstName() : "")
            + " " + (u.getLastName() != null ? u.getLastName() : "")).trim();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
