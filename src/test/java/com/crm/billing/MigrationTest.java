package com.crm.billing;

import com.crm.billing.support.AbstractBillingIT;
import com.crm.entity.BalanceTransaction;
import com.crm.entity.BillingMigrationRun;
import com.crm.entity.BillingPeriod;
import com.crm.entity.Payment;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.BalanceTransactionType;
import com.crm.entity.enums.BillingPeriodStatus;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.PaymentStatus;
import com.crm.entity.enums.UserRole;
import com.crm.exception.CodedException;
import com.crm.repository.BalanceTransactionRepository;
import com.crm.repository.BillingMigrationRunRepository;
import com.crm.repository.BillingPeriodRepository;
import com.crm.repository.GroupRepository;
import com.crm.repository.PaymentRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentRepository;
import com.crm.util.MigrationXlsx;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 6-bosqich: migratsiya (§9). Faqat H2 test bazasida — haqiqiy bazaga hech qachon ulanmaydi.
 * T = 05.10.2026, G = 18.09.2026, fee 700 000 (§9.3 jadvali).
 */
class MigrationTest extends AbstractBillingIT {

    private static final LocalDate T = LocalDate.of(2026, 10, 5);
    private static final AtomicInteger RECEIPT = new AtomicInteger(1000);

    @Autowired
    BillingMigrationService migration;
    @Autowired
    MigrationPlanner planner;
    @Autowired
    BillingProperties properties;
    @Autowired
    AccrualService accrual;
    @Autowired
    StudentGroupRepository sgRepo;
    @Autowired
    StudentRepository studentRepo;
    @Autowired
    GroupRepository groupRepo;
    @Autowired
    PaymentRepository paymentRepo;
    @Autowired
    BalanceTransactionRepository txRepo;
    @Autowired
    BillingPeriodRepository periodRepo;
    @Autowired
    BillingMigrationRunRepository runRepo;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper objectMapper;

    @AfterEach
    void enableBilling() {
        properties.setEnabled(true);
    }

    // ── eski (v1) ma'lumotni yaratish ───────────────────────────────────

    record Ids(Long student, Long group, Long sg) {
    }

    private Ids sg(long fee, String start) {
        Long student = fixtures.student();
        Long group = fixtures.group(fixtures.course(fee));
        return new Ids(student, group, fixtures.enrollment(student, group).start(d(start)).save());
    }

    /** v1 ledger yozuvi: LedgerService chetlab (eski kod shunday yozgan), balanslar SQL bilan. */
    private void legacyTx(Ids ids, BalanceTransactionType type, long amount, String date, String note) {
        inTx(() -> {
            StudentGroup sg = sgRepo.findById(ids.sg()).orElseThrow();
            BigDecimal after = sg.getBalance().add(BigDecimal.valueOf(amount));
            txRepo.save(BalanceTransaction.builder()
                .studentGroup(sg).student(sg.getStudent()).type(type)
                .amount(BigDecimal.valueOf(amount)).balanceAfter(after)
                .effectiveDate(d(date)).note(note)
                .createdAt(d(date).atTime(10, 0)).build());
        });
        jdbc.update("UPDATE student_groups SET balance = balance + ? WHERE id = ?", amount, ids.sg());
        jdbc.update("UPDATE students SET balance = balance + ? WHERE id = ?", amount, ids.student());
    }

    /** v1 to'lovi: PAYMENT +naqd va (oylar bo'yicha) PERIOD_CHARGE −naqd. */
    private Long legacyPayment(Ids ids, long gross, long discount, String date, String periodStart, boolean withCharge) {
        Long id = inTx(() -> paymentRepo.save(Payment.builder()
            .student(studentRepo.findById(ids.student()).orElseThrow())
            .group(groupRepo.findById(ids.group()).orElseThrow())
            .studentGroup(sgRepo.findById(ids.sg()).orElseThrow())
            .amount(BigDecimal.valueOf(gross))
            .discountAmount(BigDecimal.valueOf(discount))
            .payableAmount(BigDecimal.valueOf(gross - discount))
            .cashAmount(BigDecimal.valueOf(gross - discount))
            .paymentDate(d(date))
            .periodStart(periodStart != null ? d(periodStart) : null)
            .receiptNumber("OLD-" + RECEIPT.incrementAndGet())
            .status(PaymentStatus.PAID)
            .build()).getId());
        legacyTx(ids, BalanceTransactionType.PAYMENT, gross - discount, date, "To'lov");
        if (withCharge) {
            legacyTx(ids, BalanceTransactionType.PERIOD_CHARGE, -(gross - discount), date, "Davr (v1)");
        }
        return id;
    }

    private void setNext(Ids ids, String next) {
        jdbc.update("UPDATE student_groups SET next_payment_date = ? WHERE id = ?", d(next), ids.sg());
    }

    /** §9.3 jadvalining 7 qatori. */
    private Map<String, Ids> table93() {
        Map<String, Ids> m = new LinkedHashMap<>();
        Ids s1 = sg(700_000, "20.09.2026");
        legacyPayment(s1, 700_000, 0, "20.09.2026", "20.09.2026", true);
        m.put("1", s1);
        m.put("2", sg(700_000, "22.09.2026"));
        Ids s3 = sg(700_000, "18.09.2026");
        legacyPayment(s3, 1_400_000, 0, "18.09.2026", "18.09.2026", true);
        m.put("3", s3);
        Ids s4 = sg(700_000, "25.09.2026");
        legacyPayment(s4, 300_000, 0, "25.09.2026", "25.09.2026", false);
        m.put("4", s4);
        Ids s5 = sg(700_000, "01.07.2026");
        setNext(s5, "01.09.2026");
        m.put("5", s5);
        Ids s6 = sg(700_000, "01.08.2026");
        legacyPayment(s6, 700_000, 0, "20.09.2026", "01.09.2026", true);
        m.put("6", s6);
        Long st7 = fixtures.student();
        Long g7 = fixtures.group(fixtures.course(700_000));
        Ids s7 = new Ids(st7, g7, fixtures.enrollment(st7, g7).start(d("15.09.2026")).discount("10").save());
        legacyPayment(s7, 700_000, 70_000, "15.09.2026", "15.09.2026", true);
        m.put("7", s7);
        return m;
    }

    private MigrationPlanner.SgPlan row(MigrationPlanner.Report r, Ids ids) {
        return r.rows().stream().filter(x -> x.studentGroupId().equals(ids.sg())).findFirst().orElseThrow();
    }

    private long count(String table) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return n != null ? n : 0;
    }

    private static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run)
            .isInstanceOf(CodedException.class)
            .extracting(e -> ((CodedException) e).getCode())
            .isEqualTo(code);
    }

    // ── §9.3 ────────────────────────────────────────────────────────────

    @Test
    void dryRun_table93_andWritesNothing() {
        clock.setDate(T);
        Map<String, Ids> s = table93();
        String balancesBefore = jdbc.queryForList("SELECT id, balance, payment_status, next_payment_date FROM student_groups ORDER BY id").toString();
        long tx = count("balance_transactions");
        long periods = count("billing_periods");
        long runs = count("billing_migration_runs");
        long payments = count("payments");

        MigrationPlanner.Report r = migration.dryRun(T, false);

        // Hech narsa yozilmadi
        assertThat(count("balance_transactions")).isEqualTo(tx);
        assertThat(count("billing_periods")).isEqualTo(periods).isZero();
        assertThat(count("billing_migration_runs")).isEqualTo(runs).isZero();
        assertThat(count("payments")).isEqualTo(payments);
        assertThat(jdbc.queryForList("SELECT id, balance, payment_status, next_payment_date FROM student_groups ORDER BY id").toString())
            .isEqualTo(balancesBefore);

        MigrationPlanner.SgPlan r1 = row(r, s.get("1"));
        assertThat(r1.r()).isEqualTo(d("20.09.2026"));
        assertThat(r1.legacyPeriodCharges()).isEqualByComparingTo("700000");
        assertThat(r1.migrationAmount()).isEqualByComparingTo("700000");
        assertThat(r1.target()).isEqualByComparingTo("0");
        assertThat(r1.newStatus()).isEqualTo("PAID");
        assertThat(r1.newNextPaymentDate()).isEqualTo(d("20.10.2026"));

        MigrationPlanner.SgPlan r2 = row(r, s.get("2"));
        assertThat(r2.target()).isEqualByComparingTo("-700000");
        assertThat(r2.newStatus()).isEqualTo("OVERDUE");
        assertThat(r2.newDebtSince()).isEqualTo(d("22.09.2026"));

        MigrationPlanner.SgPlan r3 = row(r, s.get("3"));
        assertThat(r3.target()).isEqualByComparingTo("700000");
        assertThat(r3.newNextPaymentDate()).isEqualTo(d("18.11.2026"));

        MigrationPlanner.SgPlan r4 = row(r, s.get("4"));
        assertThat(r4.ledgerSum()).isEqualByComparingTo("300000");
        assertThat(r4.target()).isEqualByComparingTo("-400000");
        assertThat(r4.newStatus()).isEqualTo("OVERDUE");

        MigrationPlanner.SgPlan r5 = row(r, s.get("5"));
        assertThat(r5.r()).isEqualTo(d("01.09.2026"));
        assertThat(r5.periods()).extracting(MigrationPlanner.PlannedPeriod::status).containsExactly(
            BillingPeriodStatus.MIGRATED, BillingPeriodStatus.MIGRATED,
            BillingPeriodStatus.CHARGED, BillingPeriodStatus.CHARGED);
        assertThat(r5.target()).isEqualByComparingTo("-1400000");
        assertThat(r5.newDebtSince()).isEqualTo(d("01.09.2026"));
        assertThat(r5.anomalies()).contains("A12");

        MigrationPlanner.SgPlan r6 = row(r, s.get("6"));
        assertThat(r6.r()).isEqualTo(d("01.09.2026"));
        assertThat(r6.target()).isEqualByComparingTo("-700000");
        assertThat(r6.newDebtSince()).isEqualTo(d("01.10.2026"));
        assertThat(r6.newStatus()).isEqualTo("OVERDUE");

        MigrationPlanner.SgPlan r7 = row(r, s.get("7"));
        assertThat(r7.target()).isEqualByComparingTo("-70000");
        assertThat(r7.newStatus()).isEqualTo("OVERDUE");
        assertThat(r7.anomalies()).contains("A14", "A7");

        // §13 #18: A14 tasdiqlansa o'tgan davr narxi = eski payable
        MigrationPlanner.SgPlan r7b = row(migration.dryRun(T, true), s.get("7"));
        assertThat(r7b.target()).isEqualByComparingTo("0");
        assertThat(r7b.newStatus()).isEqualTo("PAID");

        assertThat(r.summary().sgTotal()).isEqualTo(7);
        assertThat(r.summary().byCategory()).containsEntry("M", 7);
        assertThat(r.summary().balanceNew()).isEqualByComparingTo("-2570000");
        assertThat(r.reportHash()).hasSize(64).isEqualTo(migration.dryRun(T, false).reportHash());
    }

    // ── Anomaliyalar ────────────────────────────────────────────────────

    /** Har kod uchun bitta SG; qaytaradi: kod → SG. */
    private Map<String, Ids> anomalyData() {
        Map<String, Ids> m = new LinkedHashMap<>();
        m.put("A1", sg(0, "20.09.2026"));
        Ids a2 = sg(700_000, "20.09.2026");
        inTx(() -> paymentRepo.save(Payment.builder().student(studentRepo.findById(a2.student()).orElseThrow())
            .amount(BigDecimal.valueOf(100_000)).cashAmount(BigDecimal.valueOf(100_000))
            .paymentDate(d("21.09.2026")).receiptNumber("OLD-" + RECEIPT.incrementAndGet())
            .status(PaymentStatus.PAID).build()));
        m.put("A2", a2);
        Ids a3 = sg(700_000, "20.09.2026");
        Long p3 = legacyPayment(a3, 700_000, 0, "20.09.2026", "20.09.2026", true);
        jdbc.update("UPDATE payments SET balance_used = 50000 WHERE id = ?", p3);
        m.put("A3", a3);
        Ids a4 = sg(700_000, "20.09.2026");
        fixtures.enrollment(a4.student(), a4.group()).start(d("20.09.2026")).save();
        m.put("A4", a4);
        Ids a5 = sg(700_000, "20.09.2026");
        setNext(a5, "01.09.2026");
        m.put("A5", a5);
        Long st6 = fixtures.student();
        Long g6 = fixtures.group(fixtures.course(700_000));
        Ids a6 = new Ids(st6, g6, fixtures.enrollment(st6, g6).start(d("20.09.2026")).trial().save());
        legacyPayment(a6, 700_000, 0, "21.09.2026", null, true);
        m.put("A6", a6);
        Long st7 = fixtures.student();
        Long g7 = fixtures.group(fixtures.course(700_000));
        m.put("A7", new Ids(st7, g7, fixtures.enrollment(st7, g7).start(d("20.09.2026")).discount("5").save()));
        Ids a8 = sg(700_000, "20.09.2026");
        legacyTx(a8, BalanceTransactionType.MANUAL_ADJUST, 700_000, "28.09.2026", MigrationPlanner.LEDGER_REPAIR_PREFIX + " eski charge");
        m.put("A8", a8);
        Ids a9 = sg(700_000, "20.09.2026");
        legacyTx(a9, BalanceTransactionType.FREEZE, 100_000, "25.09.2026", "Muzlatish (v1)");
        m.put("A9", a9);
        Ids a10 = sg(700_000, "20.09.2026");
        jdbc.update("UPDATE students SET balance = 5000 WHERE id = ?", a10.student());
        m.put("A10", a10);
        Ids a11 = sg(700_000, "20.09.2026");
        jdbc.update("UPDATE student_groups SET balance = 1000 WHERE id = ?", a11.sg());
        jdbc.update("UPDATE students SET balance = 1000 WHERE id = ?", a11.student());
        m.put("A11", a11);
        Ids a12 = sg(700_000, "01.07.2026");
        setNext(a12, "01.08.2026");
        m.put("A12", a12);
        Long st13 = fixtures.student();
        Long g13 = fixtures.group(fixtures.course(700_000, 80_000L));
        Ids a13 = new Ids(st13, g13, fixtures.enrollment(st13, g13).start(d("01.10.2026")).perLesson(80_000).save());
        legacyTx(a13, BalanceTransactionType.LESSON_CHARGE, -80_000, "28.09.2026", "Dars (v1)");
        m.put("A13", a13);
        Ids a14 = sg(700_000, "20.09.2026");
        legacyPayment(a14, 700_000, 50_000, "20.09.2026", "20.09.2026", true);
        m.put("A14", a14);
        Long st15 = fixtures.student();
        Long g15 = fixtures.group(fixtures.course(700_000));
        m.put("A15", new Ids(st15, g15, fixtures.enrollment(st15, g15).start(d("20.09.2026")).override(700_000).save()));
        Ids a16 = sg(700_000, "01.01.2024");
        setNext(a16, "01.01.2024");
        m.put("A16", a16);
        return m;
    }

    @Test
    void dryRun_reportsAllAnomalyCodes() {
        clock.setDate(T);
        Map<String, Ids> data = anomalyData();

        MigrationPlanner.Report r = migration.dryRun(T, false);

        data.forEach((code, ids) -> assertThat(row(r, ids).anomalies()).as(code).contains(code));
        assertThat(r.summary().anomalies().keySet()).containsAll(data.keySet());
        assertThat(row(r, data.get("A4")).blocking()).isTrue();
        assertThat(row(r, data.get("A16")).blocking()).isTrue();
        assertThat(row(r, data.get("A16")).periods()
            .stream().filter(p -> p.status() == BillingPeriodStatus.CHARGED)).hasSize(24);
        assertThat(row(r, data.get("A1")).periods()).isEmpty();
        assertThat(row(r, data.get("A13")).category()).isEqualTo("L");
        assertThat(row(r, data.get("A6")).category()).isEqualTo("T");
        // A8: ta'mir yozuvi neytrallanadi — MIGRATION = 0 − 700 000
        assertThat(row(r, data.get("A8")).migrationAmount()).isEqualByComparingTo("-700000");
    }

    // ── Tasdiq va apply qo'riqlari ─────────────────────────────────────

    @Test
    void approve_requiresMatchingHash_andOwner() {
        clock.setDate(T);
        table93();
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        MigrationPlanner.Report r = migration.dryRun(T, false);

        assertCode(() -> migration.approve(T, false, r.reportHash(), " ", null), "migration.ownerRequired");
        assertCode(() -> migration.approve(T, false, "bad", "Egasi", null), "migration.reportChanged");
        assertCode(() -> migration.approve(T, true, r.reportHash(), "Egasi", null), "migration.reportChanged");

        BillingMigrationRun run = migration.approve(T, false, r.reportHash(), "Egasi (buyurtmachi)", "Tasdiqlandi");
        assertThat(run.getStatus()).isEqualTo(BillingMigrationRun.Status.APPROVED);
        assertThat(run.getMaxTxId()).isEqualTo(r.maxTxId());
        assertThat(count("billing_migration_runs")).isEqualTo(1);
        assertThat(count("billing_periods")).isZero();
    }

    @Test
    void apply_guards() {
        clock.setDate(T);
        Map<String, Ids> s = table93();
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        MigrationPlanner.Report r = migration.dryRun(T, false);
        Long runId = migration.approve(T, false, r.reportHash(), "Egasi", null).getId();

        assertCode(() -> migration.apply(runId, null, List.of(), false), "migration.confirmRequired");
        assertCode(() -> migration.apply(runId, "APPLY-" + runId, List.of(), false), "migration.billingEnabled");

        properties.setEnabled(false);
        legacyPayment(s.get("2"), 100_000, 0, "04.10.2026", null, false);   // tasdiqdan keyin o'zgarish
        assertCode(() -> migration.apply(runId, "APPLY-" + runId, List.of(), false), "migration.reportChanged");
        assertThat(count("billing_periods")).isZero();
    }

    @Test
    void apply_writesExactlyTheReport_holdsExcludedAndBlocking_verifyOk() {
        clock.setDate(T);
        Map<String, Ids> s = table93();
        Ids dup = sg(700_000, "20.09.2026");
        fixtures.enrollment(dup.student(), dup.group()).start(d("20.09.2026")).save();   // A4
        Ids frozenLegacy = sg(700_000, "20.09.2026");
        jdbc.update("UPDATE student_groups SET is_active = FALSE, exit_reason = 'FROZEN', exit_date = ? WHERE id = ?",
            d("01.10.2026"), frozenLegacy.sg());
        Long st = fixtures.student();
        Long g = fixtures.group(fixtures.course(700_000));
        Ids finalOverride = new Ids(st, g, fixtures.enrollment(st, g).start(d("20.09.2026")).override(700_000).save());

        fixtures.loginAs(UserRole.SUPER_ADMIN);
        MigrationPlanner.Report r = migration.dryRun(T, false);
        Long runId = migration.approve(T, false, r.reportHash(), "Egasi", null).getId();
        properties.setEnabled(false);

        BillingMigrationService.ApplyResult res = migration.apply(runId, "APPLY-" + runId,
            List.of(s.get("5").sg()), true);

        assertThat(res.run().getStatus()).isEqualTo(BillingMigrationRun.Status.APPLIED);
        assertThat(res.run().getRollbackDeadline()).isEqualTo(res.run().getAppliedAt().plusHours(72));
        assertThat(res.held()).contains(s.get("5").sg(), dup.sg());
        for (String k : List.of("1", "2", "3", "4", "6", "7")) {
            Ids ids = s.get(k);
            StudentGroup after = inTx(() -> sgRepo.findById(ids.sg()).orElseThrow());
            assertThat(after.getBalance()).as("SG " + k).isEqualByComparingTo(row(r, ids).target());
            assertThat(after.getPaymentStatus().name()).as("SG " + k).isEqualTo(row(r, ids).newStatus());
            assertThat(after.getBillingHold()).isNull();
        }
        List<BalanceTransaction> l1 = inTx(() -> txRepo.findByMigrationRunIdAndStudentGroup_Id(runId, s.get("1").sg()));
        assertThat(l1).extracting(BalanceTransaction::getType)
            .containsExactly(BalanceTransactionType.PERIOD_CHARGE, BalanceTransactionType.MIGRATION);

        // Chetlatilgan (A12) va bloklovchi (A4): yozuv yo'q, hold, accrual o'tkazib yuboradi
        Ids five = s.get("5");
        assertThat(inTx(() -> periodRepo.findByStudentGroupIdOrderByPeriodStartAsc(five.sg()))).isEmpty();
        assertThat(inTx(() -> sgRepo.findById(five.sg()).orElseThrow()).getBillingHold()).isTrue();
        assertThat(inTx(() -> sgRepo.findById(dup.sg()).orElseThrow()).getBillingHold()).isTrue();

        // Eski muzlatilgan SG: frozen_from to'ldirildi; A15: override tozalandi
        assertThat(inTx(() -> sgRepo.findById(frozenLegacy.sg()).orElseThrow()).getFrozenFrom())
            .isEqualTo(d("01.10.2026"));
        assertThat(inTx(() -> sgRepo.findById(finalOverride.sg()).orElseThrow()).getMonthlyPriceOverride()).isNull();

        // Qayta apply yo'q; billing yoqilgach accrual migratsiya davrlaridan davom etadi
        assertCode(() -> migration.apply(runId, "APPLY-" + runId, List.of(), false), "migration.notApproved");
        properties.setEnabled(true);
        clock.setDate(d("20.10.2026"));
        accrual.accrueUpTo(s.get("1").sg(), d("20.10.2026"));
        accrual.accrueUpTo(five.sg(), d("20.10.2026"));
        assertThat(inTx(() -> periodRepo.findByStudentGroupIdOrderByPeriodStartAsc(s.get("1").sg())))
            .extracting(BillingPeriod::getPeriodStart).containsExactly(d("20.09.2026"), d("20.10.2026"));
        assertThat(inTx(() -> periodRepo.findByStudentGroupIdOrderByPeriodStartAsc(five.sg()))).isEmpty();

        BillingMigrationService.Verification v = migration.verify();
        assertThat(v.sgBalanceMismatch()).isEmpty();
        assertThat(v.chargedPeriodsWithoutLedger()).isEmpty();
        assertThat(v.paymentsWithoutCash()).isEmpty();
        assertThat(v.heldEnrollments()).contains(five.sg(), dup.sg());
    }

    @Test
    void revertSg_thenApplySg() {
        clock.setDate(T);
        Map<String, Ids> s = table93();
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        MigrationPlanner.Report r = migration.dryRun(T, false);
        Long runId = migration.approve(T, false, r.reportHash(), "Egasi", null).getId();
        properties.setEnabled(false);
        migration.apply(runId, "APPLY-" + runId, List.of(), false);
        Ids six = s.get("6");

        assertCode(() -> migration.revertSg(runId, six.sg(), "REVERT"), "migration.confirmRequired");
        BillingMigrationService.RevertResult rev = migration.revertSg(runId, six.sg(), "REVERT-" + runId + "-" + six.sg());

        assertThat(rev.reversed()).isEqualTo(3);         // 2 × PERIOD_CHARGE + MIGRATION
        assertThat(rev.periodsDeleted()).isEqualTo(3);   // 01.08 MIGRATED, 01.09 va 01.10 CHARGED
        assertThat(rev.balanceAfter()).isEqualByComparingTo("0");
        StudentGroup held = inTx(() -> sgRepo.findById(six.sg()).orElseThrow());
        assertThat(held.getBillingHold()).isTrue();
        assertThat(inTx(() -> periodRepo.findByStudentGroupIdOrderByPeriodStartAsc(six.sg()))).isEmpty();

        MigrationPlanner.SgPlan again = migration.applySg(runId, six.sg(), "APPLY-" + runId + "-" + six.sg());
        assertThat(again.alreadyMigrated()).isTrue();
        StudentGroup after = inTx(() -> sgRepo.findById(six.sg()).orElseThrow());
        assertThat(after.getBillingHold()).isNull();
        assertThat(after.getBalance()).isEqualByComparingTo("-700000");
        assertCode(() -> migration.applySg(runId, six.sg(), "APPLY-" + runId + "-" + six.sg()), "migration.sgNotHeld");
    }

    // ── Namuna hisobot (test ma'lumotida) ───────────────────────────────

    /** 5-bosqich yakuniy hisobot uchun: target/billing-v2-dry-run-sample.{json,xlsx}. */
    @Test
    void writeSampleDryRunReport() throws Exception {
        clock.setDate(T);
        table93();
        anomalyData();
        Long completed = fixtures.group(fixtures.course(700_000), GroupStatus.COMPLETED);
        fixtures.enrollment(fixtures.student(), completed).start(d("20.09.2026")).save();

        MigrationPlanner.Report r = migration.dryRun(T, false);

        Path dir = Path.of("target");
        Files.createDirectories(dir);
        ObjectMapper om = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        Files.writeString(dir.resolve("billing-v2-dry-run-sample.json"), om.writeValueAsString(r));
        Files.write(dir.resolve("billing-v2-dry-run-sample.xlsx"), MigrationXlsx.dryRun(r));
        assertThat(r.rows()).hasSizeGreaterThan(20);
        assertThat(count("billing_periods")).isZero();
    }
}
