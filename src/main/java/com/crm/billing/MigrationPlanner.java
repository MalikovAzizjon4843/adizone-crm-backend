package com.crm.billing;

import com.crm.entity.BalanceTransaction;
import com.crm.entity.Course;
import com.crm.entity.Payment;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.BalanceTransactionType;
import com.crm.entity.enums.BillingPeriodStatus;
import com.crm.entity.enums.PaymentStatus;
import com.crm.entity.enums.PaymentType;
import com.crm.repository.BalanceTransactionRepository;
import com.crm.repository.BillingPeriodRepository;
import com.crm.repository.PaymentRepository;
import com.crm.repository.StudentGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Migratsiya rejasi (§9.2–§9.4) — FAQAT O'QIYDI. Dry-run, tasdiq (hash) va apply bitta
 * shu hisobdan foydalanadi: apply yozadigan qiymatlar aynan hisobotdagilar.
 *
 * <p>Toifalar: M (MONTHLY, sinovda emas, faol) — davrlar R dan T gacha qayta quriladi,
 * eski davr debetlari bitta {@code MIGRATION} bilan neytrallanadi; L (PER_LESSON), T (sinov),
 * F (muzlatilgan / yopilgan) — yozuv yo'q, faqat tekshiruv.
 */
@Service
@RequiredArgsConstructor
public class MigrationPlanner {

    public static final String LEDGER_REPAIR_PREFIX = "[ledger-repair]";
    /** Apply bularni avtomatik chetlatadi (hold) — qo'lda ko'rish shart. */
    public static final Set<String> BLOCKING = Set.of("A4", "A16", "NO_ANCHOR");

    private final StudentGroupRepository studentGroupRepository;
    private final BalanceTransactionRepository transactionRepository;
    private final PaymentRepository paymentRepository;
    private final BillingPeriodRepository periodRepository;
    private final BillingStatusService statusService;
    private final BillingProperties properties;
    private final Clock billingClock;

    public record Options(LocalDate cutover, LocalDate goLive, boolean a14UsePayable) {
    }

    public record PlannedPeriod(LocalDate start, LocalDate end, BillingPeriodStatus status,
                                BigDecimal fee, BigDecimal amount) {
    }

    /** Hisobot qatori (§9.4 "Har SG qatori"). */
    public record SgPlan(
        Long studentGroupId, Long studentId, String studentName, Long groupId, String groupName,
        String category, PaymentType paymentType, LocalDate paymentStartDate, LocalDate r, BigDecimal fee,
        List<PlannedPeriod> periods,
        BigDecimal ledgerSum, BigDecimal storedBalance, BigDecimal legacyPeriodCharges,
        BigDecimal repairAdjustments, BigDecimal migrationAmount, BigDecimal charges,
        BigDecimal target, BigDecimal delta,
        String oldStatus, LocalDate oldNextPaymentDate, BigDecimal oldDebt,
        String newStatus, BigDecimal newDebt, LocalDate newDebtSince,
        LocalDate newNextPaymentDate, BigDecimal newNextPaymentAmount,
        SortedSet<String> anomalies, boolean blocking, boolean alreadyMigrated) {

        /** Apply nimani yozadi: davrlar yoki MIGRATION yozuvi bor-yo'qligi. */
        public boolean writes() {
            return "M".equals(category) && !alreadyMigrated
                && (!periods.isEmpty() || migrationAmount.signum() != 0);
        }
    }

    public record Summary(
        int sgTotal, Map<String, Integer> byCategory, Map<String, Integer> anomalies, int blocking,
        int alreadyMigrated,
        int debtorsOld, int debtorsNew, BigDecimal debtOld, BigDecimal debtNew,
        BigDecimal balanceOld, BigDecimal balanceNew, Map<String, Integer> transitions,
        int periodsCharged, int periodsMigrated, int chargeEntries, int migrationEntries) {
    }

    public record Report(LocalDate cutover, LocalDate goLive, boolean a14UsePayable,
                         Long maxTxId, Long maxPaymentId, LocalDateTime generatedAt,
                         String reportHash, Summary summary, List<SgPlan> rows) {
    }

    /** O'quvchi darajasidagi tekshiruvlar uchun (A2, A4, A10). */
    private record StudentFacts(boolean hasUnlinkedPayments, boolean duplicateActive, boolean balanceMismatch) {
    }

    // ── Dry-run ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Report dryRun(Options o) {
        Long maxTx = transactionRepository.findMaxId();
        Long maxPay = paymentRepository.findMaxId();
        List<StudentGroup> all = studentGroupRepository.findAll(Sort.by("id"));
        Map<Long, StudentFacts> facts = studentFacts(all);

        List<SgPlan> rows = new ArrayList<>();
        for (StudentGroup sg : all) {
            rows.add(plan(sg, o, facts.get(sg.getStudent().getId())));
        }
        Summary summary = summarize(rows);
        String hash = hash(o, maxTx, maxPay, rows);
        return new Report(o.cutover(), o.goLive(), o.a14UsePayable(), maxTx, maxPay,
            LocalDateTime.now(billingClock), hash, summary, rows);
    }

    /** Apply'da bitta SG ni qulf ostida qayta hisoblash uchun. */
    @Transactional(readOnly = true)
    public SgPlan planOne(StudentGroup sg, Options o) {
        List<StudentGroup> mine = studentGroupRepository.findByStudentId(sg.getStudent().getId());
        return plan(sg, o, studentFacts(mine).get(sg.getStudent().getId()));
    }

    private Map<Long, StudentFacts> studentFacts(List<StudentGroup> all) {
        Map<Long, List<StudentGroup>> byStudent = new HashMap<>();
        for (StudentGroup sg : all) {
            byStudent.computeIfAbsent(sg.getStudent().getId(), k -> new ArrayList<>()).add(sg);
        }
        Map<Long, StudentFacts> out = new HashMap<>();
        byStudent.forEach((studentId, sgs) -> {
            boolean unlinked = !paymentRepository
                .findByStudent_IdAndStudentGroupIsNullAndStatus(studentId, PaymentStatus.PAID).isEmpty();
            Map<Long, Integer> activePerGroup = new HashMap<>();
            BigDecimal sum = BigDecimal.ZERO;
            for (StudentGroup sg : sgs) {
                sum = sum.add(Money.nz(sg.getBalance()));
                if (Boolean.TRUE.equals(sg.getIsActive()) && sg.getGroup() != null) {
                    activePerGroup.merge(sg.getGroup().getId(), 1, Integer::sum);
                }
            }
            boolean dup = activePerGroup.values().stream().anyMatch(c -> c > 1);
            BigDecimal studentBalance = Money.nz(sgs.get(0).getStudent().getBalance());
            out.put(studentId, new StudentFacts(unlinked, dup, !Money.eq(studentBalance, sum)));
        });
        return out;
    }

    // ── Bitta SG ────────────────────────────────────────────────────────

    private SgPlan plan(StudentGroup sg, Options o, StudentFacts facts) {
        LocalDate t = o.cutover();
        SortedSet<String> anomalies = new TreeSet<>();
        List<BalanceTransaction> ledger = transactionRepository.findByStudentGroup_IdOrderByIdAsc(sg.getId());
        List<Payment> payments = paymentRepository
            .findByStudentGroup_IdAndStatusOrderByPaymentDateAscIdAsc(sg.getId(), PaymentStatus.PAID);

        BigDecimal l = BigDecimal.ZERO;
        BigDecimal legacyPc = BigDecimal.ZERO;
        BigDecimal repair = BigDecimal.ZERO;
        boolean freezeRows = false;
        boolean lessonBeforeAnchor = false;
        for (BalanceTransaction tx : ledger) {
            l = l.add(tx.getAmount());
            if (tx.getType() == BalanceTransactionType.PERIOD_CHARGE
                    && tx.getBillingPeriodId() == null && tx.getMigrationRunId() == null) {
                legacyPc = legacyPc.add(tx.getAmount().abs());
            }
            if (tx.getType() == BalanceTransactionType.MANUAL_ADJUST && tx.getNote() != null
                    && tx.getNote().startsWith(LEDGER_REPAIR_PREFIX)) {
                repair = repair.add(tx.getAmount());
            }
            if (tx.getType() == BalanceTransactionType.FREEZE || tx.getType() == BalanceTransactionType.UNFREEZE) {
                freezeRows = true;
            }
            if (tx.getType() == BalanceTransactionType.LESSON_CHARGE && sg.getPaymentStartDate() != null
                    && tx.getEffectiveDate() != null && tx.getEffectiveDate().isBefore(sg.getPaymentStartDate())) {
                lessonBeforeAnchor = true;
            }
        }
        // Qaytarilgan (revert-sg) migratsiya yozuvlari hisobga olinmaydi — SG qayta qo'llanishi mumkin
        Set<Long> reversed = new java.util.HashSet<>();
        ledger.stream().filter(tx -> tx.getType() == BalanceTransactionType.REVERSAL && tx.getRelatedTxId() != null)
            .forEach(tx -> reversed.add(tx.getRelatedTxId()));
        boolean alreadyMigrated = !periodRepository.findByStudentGroupIdOrderByPeriodStartAsc(sg.getId()).isEmpty()
            || ledger.stream().anyMatch(tx -> tx.getMigrationRunId() != null && !reversed.contains(tx.getId())
                && tx.getType() != BalanceTransactionType.REVERSAL);

        // Umumiy anomaliyalar
        if (facts != null && facts.hasUnlinkedPayments()) anomalies.add("A2");
        if (payments.stream().anyMatch(p -> Money.nz(p.getBalanceUsed()).signum() > 0)) anomalies.add("A3");
        if (facts != null && facts.duplicateActive() && Boolean.TRUE.equals(sg.getIsActive())) anomalies.add("A4");
        if (Money.nz(sg.getDiscountPercentage()).signum() > 0) anomalies.add("A7");
        if (repair.signum() != 0) anomalies.add("A8");
        if (facts != null && facts.balanceMismatch()) anomalies.add("A10");
        if (!Money.eq(Money.nz(sg.getBalance()), l)) anomalies.add("A11");
        if (payments.stream().anyMatch(p -> Money.nz(p.getDiscountAmount()).signum() > 0)) anomalies.add("A14");
        Course course = sg.getGroup() != null ? sg.getGroup().getCourse() : null;
        if (sg.getMonthlyPriceOverride() != null && course != null && course.getMonthlyPrice() != null
                && Money.eq(sg.getMonthlyPriceOverride(), course.getMonthlyPrice())) {
            anomalies.add("A15");
        }

        String category = category(sg);
        LocalDate anchor = sg.getPaymentStartDate() != null ? sg.getPaymentStartDate() : sg.getJoinDate();
        BigDecimal fee = EnrollmentPricing.monthlyFee(sg);
        List<PlannedPeriod> periods = new ArrayList<>();
        LocalDate r = null;
        BigDecimal charges = BigDecimal.ZERO;
        BigDecimal migration = BigDecimal.ZERO;

        switch (category) {
            case "L" -> {
                if (lessonBeforeAnchor) anomalies.add("A13");
            }
            case "T" -> {
                boolean hasCharge = ledger.stream().anyMatch(tx -> tx.getType() == BalanceTransactionType.PERIOD_CHARGE);
                if (!payments.isEmpty() || hasCharge) anomalies.add("A6");
            }
            case "F" -> {
                if (freezeRows || (EnrollmentLifecycleService.isFrozen(sg) && l.signum() != 0)) anomalies.add("A9");
            }
            default -> {
                if (freezeRows) anomalies.add("A9");
                if (anchor == null) {
                    anomalies.add("NO_ANCHOR");
                    break;
                }
                if (fee.signum() <= 0) anomalies.add("A1");
                r = startingPoint(sg, anchor, payments, o.goLive());
                if (sg.getNextPaymentDate() != null && sg.getNextPaymentDate().isBefore(r)) anomalies.add("A5");
                if (alreadyMigrated) {
                    break;
                }

                AccrualCalculator.State state = AccrualCalculator.State.of(sg);
                Map<LocalDate, BigDecimal> payableByStart = o.a14UsePayable() ? payableByPeriod(anchor, payments) : Map.of();
                int charged = 0;
                for (int n = 0; ; n++) {
                    LocalDate start = BillingCalendar.start(anchor, n);
                    if (start.isAfter(t)) {
                        break;
                    }
                    LocalDate end = BillingCalendar.end(anchor, n);
                    if (start.isBefore(r)) {
                        periods.add(new PlannedPeriod(start, end, BillingPeriodStatus.MIGRATED, fee, BigDecimal.ZERO));
                        continue;
                    }
                    if (!AccrualCalculator.isAccruable(state, start)) {
                        break;
                    }
                    if (charged >= properties.getMaxCatchUp()) {
                        anomalies.add("A16");
                        break;
                    }
                    // §9.2: o'tgan davrlarga discountPercentage qo'llanmaydi (c_n = fee); A14 — tasdiqlansa payable
                    BigDecimal c = payableByStart.getOrDefault(start, fee);
                    periods.add(new PlannedPeriod(start, end, BillingPeriodStatus.CHARGED, fee, c));
                    charges = charges.add(c);
                    charged++;
                }
                migration = legacyPc.subtract(repair);
            }
        }

        BigDecimal target = l.add(migration).subtract(charges);
        if ("M".equals(category) && fee.signum() > 0 && target.subtract(l).abs().compareTo(fee) > 0) {
            anomalies.add("A12");
        }

        // Yangi holat — apply yozadigan qatorlar ustida (yozmasdan)
        List<FifoDebt.Line> planned = new ArrayList<>();
        List<LocalDate> plannedStarts = new ArrayList<>();
        if (migration.signum() != 0) {
            planned.add(new FifoDebt.Line(null, migration, t, null, true));      // MIGRATION — neytral juftlik
        }
        for (PlannedPeriod p : periods) {
            plannedStarts.add(p.start());
            if (p.status() == BillingPeriodStatus.CHARGED && p.amount().signum() > 0) {
                planned.add(new FifoDebt.Line(null, p.amount().negate(), p.start(), null));
            }
        }
        BillingSnapshot after = statusService.snapshotWith(sg, planned, plannedStarts, t);
        PaymentStatus newStatus = statusService.displayStatus(sg, after.balance(), after.debtSince(), t);
        if (newStatus == PaymentStatus.PAID && EnrollmentLifecycleService.isFrozen(sg)) {
            newStatus = PaymentStatus.FROZEN;
        }

        boolean blocking = anomalies.stream().anyMatch(BLOCKING::contains);
        BigDecimal stored = Money.nz(sg.getBalance());
        return new SgPlan(
            sg.getId(), sg.getStudent().getId(),
            sg.getStudent().getFirstName() + " " + sg.getStudent().getLastName(),
            sg.getGroup() != null ? sg.getGroup().getId() : null,
            sg.getGroup() != null ? sg.getGroup().getGroupName() : null,
            category, sg.getPaymentType(), sg.getPaymentStartDate(), r, Money.normalize(fee),
            periods,
            Money.normalize(l), Money.normalize(stored), Money.normalize(legacyPc), Money.normalize(repair),
            Money.normalize(migration), Money.normalize(charges), Money.normalize(target),
            Money.normalize(target.subtract(l)),
            sg.getPaymentStatus() != null ? sg.getPaymentStatus().name() : null, sg.getNextPaymentDate(),
            Money.normalize(stored.signum() < 0 ? stored.negate() : BigDecimal.ZERO),
            newStatus.name(), Money.normalize(after.debt()), after.debtSince(),
            after.nextPaymentDate(), after.nextPaymentAmount() != null ? Money.normalize(after.nextPaymentAmount()) : null,
            anomalies, blocking, alreadyMigrated);
    }

    static String category(StudentGroup sg) {
        if (sg.getPaymentType() == PaymentType.PER_LESSON) {
            return "L";
        }
        if (Boolean.TRUE.equals(sg.getIsTrial())) {
            return "T";
        }
        if (!Boolean.TRUE.equals(sg.getIsActive()) || sg.getFrozenFrom() != null) {
            return "F";
        }
        return "M";
    }

    /**
     * R (§9.2): langar G dan keyin — langar; aks holda (import) CRM to'lovi yo'q — eski
     * {@code nextPaymentDate} (to'rga moslab); bor — min(G dan keyingi birinchi davr,
     * eng erta to'lov davri).
     */
    static LocalDate startingPoint(StudentGroup sg, LocalDate anchor, List<Payment> payments, LocalDate goLive) {
        if (!anchor.isBefore(goLive)) {
            return anchor;
        }
        LocalDate firstAfterG = firstStartOnOrAfter(anchor, goLive);
        if (payments.isEmpty()) {
            LocalDate next = sg.getNextPaymentDate();
            return next != null ? gridFloor(anchor, next) : firstAfterG;
        }
        LocalDate earliest = payments.stream()
            .map(p -> gridFloor(anchor, p.getPeriodStart() != null ? p.getPeriodStart() : p.getPaymentDate()))
            .filter(Objects::nonNull)
            .min(LocalDate::compareTo)
            .orElse(firstAfterG);
        return earliest.isBefore(firstAfterG) ? earliest : firstAfterG;
    }

    static LocalDate gridFloor(LocalDate anchor, LocalDate date) {
        if (date == null) {
            return null;
        }
        int n = BillingCalendar.indexOf(anchor, date);
        return n < 0 ? anchor : BillingCalendar.start(anchor, n);
    }

    static LocalDate firstStartOnOrAfter(LocalDate anchor, LocalDate date) {
        int n = BillingCalendar.indexOf(anchor, date);
        if (n < 0) {
            return anchor;
        }
        LocalDate s = BillingCalendar.start(anchor, n);
        return s.isBefore(date) ? BillingCalendar.start(anchor, n + 1) : s;
    }

    /** A14 (§13 #18): chegirmali eski to'lov qoplagan davr narxi = o'sha to'lovning payable qiymati. */
    private static Map<LocalDate, BigDecimal> payableByPeriod(LocalDate anchor, List<Payment> payments) {
        Map<LocalDate, BigDecimal> out = new HashMap<>();
        for (Payment p : payments) {
            if (Money.nz(p.getDiscountAmount()).signum() <= 0) {
                continue;
            }
            LocalDate start = gridFloor(anchor, p.getPeriodStart() != null ? p.getPeriodStart() : p.getPaymentDate());
            BigDecimal payable = p.getPayableAmount() != null ? p.getPayableAmount()
                : Money.nz(p.getAmount()).subtract(Money.nz(p.getDiscountAmount()));
            if (start != null && payable.signum() > 0) {
                out.put(start, Money.uzs(payable));
            }
        }
        return out;
    }

    // ── Jami va hash ────────────────────────────────────────────────────

    private Summary summarize(List<SgPlan> rows) {
        Map<String, Integer> byCategory = new TreeMap<>();
        Map<String, Integer> anomalies = new TreeMap<>();
        Map<String, Integer> transitions = new TreeMap<>();
        Set<Long> debtorsOld = new TreeSet<>();
        Set<Long> debtorsNew = new TreeSet<>();
        BigDecimal debtOld = BigDecimal.ZERO;
        BigDecimal debtNew = BigDecimal.ZERO;
        BigDecimal balanceOld = BigDecimal.ZERO;
        BigDecimal balanceNew = BigDecimal.ZERO;
        int blocking = 0;
        int migrated = 0;
        int charged = 0;
        int migratedPeriods = 0;
        int chargeEntries = 0;
        int migrationEntries = 0;
        for (SgPlan r : rows) {
            byCategory.merge(r.category(), 1, Integer::sum);
            r.anomalies().forEach(a -> anomalies.merge(a, 1, Integer::sum));
            transitions.merge(r.oldStatus() + "→" + r.newStatus(), 1, Integer::sum);
            if ("OVERDUE".equals(r.oldStatus())) debtorsOld.add(r.studentId());
            if ("OVERDUE".equals(r.newStatus())) debtorsNew.add(r.studentId());
            debtOld = debtOld.add(r.oldDebt());
            debtNew = debtNew.add(r.newDebt());
            balanceOld = balanceOld.add(r.ledgerSum());
            balanceNew = balanceNew.add(r.target());
            if (r.blocking()) blocking++;
            if (r.alreadyMigrated()) migrated++;
            for (PlannedPeriod p : r.periods()) {
                if (p.status() == BillingPeriodStatus.CHARGED) {
                    charged++;
                    if (p.amount().signum() > 0) chargeEntries++;
                } else {
                    migratedPeriods++;
                }
            }
            if (r.migrationAmount().signum() != 0) migrationEntries++;
        }
        return new Summary(rows.size(), byCategory, anomalies, blocking, migrated,
            debtorsOld.size(), debtorsNew.size(), debtOld, debtNew, balanceOld, balanceNew, transitions,
            charged, migratedPeriods, chargeEntries, migrationEntries);
    }

    static String hash(Options o, Long maxTx, Long maxPay, List<SgPlan> rows) {
        StringBuilder sb = new StringBuilder()
            .append(o.cutover()).append('|').append(o.goLive()).append('|').append(o.a14UsePayable())
            .append('|').append(maxTx).append('|').append(maxPay);
        for (SgPlan r : rows) {
            sb.append('\n').append(r.studentGroupId()).append(':').append(r.category()).append(':').append(r.r())
                .append(':').append(r.ledgerSum().toPlainString()).append(':').append(r.migrationAmount().toPlainString())
                .append(':').append(r.charges().toPlainString()).append(':').append(r.target().toPlainString())
                .append(':').append(r.anomalies());
            for (PlannedPeriod p : r.periods()) {
                sb.append(';').append(p.start()).append(p.status().name().charAt(0)).append(p.amount().toPlainString());
            }
        }
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
