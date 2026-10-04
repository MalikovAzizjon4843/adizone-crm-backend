package com.crm.billing;

import com.crm.billing.support.AbstractBillingIT;
import com.crm.entity.BalanceTransaction;
import com.crm.entity.BillingPeriod;
import com.crm.entity.Payment;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.BalanceTransactionType;
import com.crm.entity.enums.PaymentStatus;
import com.crm.entity.enums.UserRole;
import com.crm.exception.CodedException;
import com.crm.repository.BalanceTransactionRepository;
import com.crm.repository.BillingHeldApplicationRepository;
import com.crm.repository.BillingPeriodRepository;
import com.crm.repository.GroupRepository;
import com.crm.repository.PaymentRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Hold'dagi yozilmalar CRM'dan (SA): ro'yxat, preview (yangi langar bilan), apply (Idempotency-Key, sabab). */
class HeldEnrollmentTest extends AbstractBillingIT {

    private static final LocalDate T = LocalDate.of(2026, 10, 5);

    @Autowired HeldEnrollmentService held;
    @Autowired MigrationPlanner planner;
    @Autowired BillingMigrationService migration;
    @Autowired BillingProperties properties;
    @Autowired StudentGroupRepository sgRepo;
    @Autowired StudentRepository studentRepo;
    @Autowired GroupRepository groupRepo;
    @Autowired PaymentRepository paymentRepo;
    @Autowired BalanceTransactionRepository txRepo;
    @Autowired BillingPeriodRepository periodRepo;
    @Autowired BillingHeldApplicationRepository applicationRepo;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void enableBilling() {
        properties.setEnabled(true);
    }

    record Ids(Long student, Long group, Long sg) {
    }

    /** v1 yozilma: 20.09 langar, 20.09 da 700 000 to'lov va v1 PERIOD_CHARGE (LedgerService chetlab, eski kod kabi). */
    private Ids legacy() {
        Long student = fixtures.student();
        Long group = fixtures.group(fixtures.course(700_000));
        Long sg = fixtures.enrollment(student, group).start(d("20.09.2026")).save();
        inTx(() -> paymentRepo.save(Payment.builder()
            .student(studentRepo.findById(student).orElseThrow())
            .group(groupRepo.findById(group).orElseThrow())
            .studentGroup(sgRepo.findById(sg).orElseThrow())
            .amount(BigDecimal.valueOf(700_000)).discountAmount(BigDecimal.ZERO)
            .payableAmount(BigDecimal.valueOf(700_000)).cashAmount(BigDecimal.valueOf(700_000))
            .paymentDate(d("20.09.2026")).periodStart(d("20.09.2026"))
            .receiptNumber("OLD-" + sg).status(PaymentStatus.PAID).build()));
        for (long amount : new long[]{700_000, -700_000}) {
            inTx(() -> {
                StudentGroup e = sgRepo.findById(sg).orElseThrow();
                txRepo.save(BalanceTransaction.builder().studentGroup(e).student(e.getStudent())
                    .type(amount > 0 ? BalanceTransactionType.PAYMENT : BalanceTransactionType.PERIOD_CHARGE)
                    .amount(BigDecimal.valueOf(amount)).balanceAfter(BigDecimal.ZERO)
                    .effectiveDate(d("20.09.2026")).note("v1").createdAt(d("20.09.2026").atTime(10, 0)).build());
            });
        }
        return new Ids(student, group, sg);
    }

    /** Migratsiya qo'llangan, {@code heldOne} chetlatilgan → hold. */
    private Ids appliedRunWithHeld() {
        clock.setDate(T);
        legacy();
        Ids heldOne = legacy();
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        MigrationPlanner.Report r = migration.dryRun(T, false);
        Long runId = migration.approve(T, false, r.reportHash(), "Egasi", null).getId();
        properties.setEnabled(false);
        migration.apply(runId, "APPLY-" + runId, List.of(heldOne.sg()), false);
        properties.setEnabled(true);
        assertThat(inTx(() -> sgRepo.findById(heldOne.sg()).orElseThrow()).getBillingHold()).isTrue();
        return heldOne;
    }

    private Ids enrollment(String anchor) {
        Long student = fixtures.student();
        Long group = fixtures.group(fixtures.course(700_000));
        return new Ids(student, group, fixtures.enrollment(student, group).start(d(anchor)).save());
    }

    /**
     * v1 to'lovi va uning ledger yozuvlari — eski PaymentService kabi: PAYMENT = naqd (gross − chegirma),
     * PERIOD_CHARGE = −(oylar × fee − chegirma), {@code periodCharge = 0} — debet yozilmagan.
     */
    private void v1Payment(Ids ids, long gross, long discount, String date, long periodCharge) {
        long payable = gross - discount;
        inTx(() -> paymentRepo.save(Payment.builder()
            .student(studentRepo.findById(ids.student()).orElseThrow())
            .group(groupRepo.findById(ids.group()).orElseThrow())
            .studentGroup(sgRepo.findById(ids.sg()).orElseThrow())
            .amount(BigDecimal.valueOf(gross)).discountAmount(BigDecimal.valueOf(discount))
            .payableAmount(BigDecimal.valueOf(payable)).cashAmount(BigDecimal.valueOf(payable))
            .paymentDate(d(date)).periodStart(d(date))
            .receiptNumber("OLD-" + ids.sg() + "-" + gross + "-" + date).status(PaymentStatus.PAID).build()));
        if (payable > 0) {
            v1Tx(ids.sg(), BalanceTransactionType.PAYMENT, payable, date);
        }
        if (periodCharge > 0) {
            v1Tx(ids.sg(), BalanceTransactionType.PERIOD_CHARGE, -periodCharge, date);
        }
        jdbc.update("UPDATE student_groups SET balance = (SELECT COALESCE(SUM(amount), 0) FROM balance_transactions"
            + " WHERE student_group_id = ?) WHERE id = ?", ids.sg(), ids.sg());
    }

    private void v1Tx(Long sg, BalanceTransactionType type, long amount, String date) {
        inTx(() -> {
            StudentGroup e = sgRepo.findById(sg).orElseThrow();
            txRepo.save(BalanceTransaction.builder().studentGroup(e).student(e.getStudent())
                .type(type).amount(BigDecimal.valueOf(amount)).balanceAfter(BigDecimal.ZERO)
                .effectiveDate(d(date)).note("v1").createdAt(d(date).atTime(10, 0)).build());
        });
    }

    private MigrationPlanner.SgPlan plan(Ids ids, MigrationPlanner.Options o) {
        return inTx(() -> planner.planOne(sgRepo.findById(ids.sg()).orElseThrow(), o));
    }

    /**
     * Prod misollari (fee 700 000, T = 05.10, A14 tasdiqlangan run): eski reja v1 ko'p oylik chegirmali to'lov
     * payable'ini bitta davrga qo'yib, qolgan oylarni yana fee bilan hisoblardi. Hold rejasi — sof hisob:
     * to'lovlar (gross) − davrlar × fee.
     */
    @Test
    void heldPlan_isNet_prodExamples() {
        clock.setDate(T);
        legacy();                                                         // oddiy SG — migratsiya qilinadi
        Ids sg4 = enrollment("03.08.2026");
        v1Payment(sg4, 1_400_000, 399_999, "03.08.2026", 1_000_001);       // 2 oy, payable 1 000 001
        v1Payment(sg4, 700_000, 0, "03.10.2026", 0);                       // debetsiz — balans +700 000
        Ids sg6 = enrollment("29.08.2026");
        v1Payment(sg6, 1_400_000, 100_000, "29.08.2026", 1_300_000);       // 2 oy
        Ids sg46 = enrollment("01.09.2026");
        v1Payment(sg46, 700_000, 700_000, "01.09.2026", 0);               // 100% chegirma: naqd 0, debet yo'q
        Ids sg47 = enrollment("01.06.2026");
        v1Payment(sg47, 2_800_000, 200_000, "01.06.2026", 2_600_000);      // 4 oy
        List<Ids> heldOnes = List.of(sg4, sg6, sg46, sg47);

        // Eski (bulk, A14) reja — prod'dagi raqamlar takrorlanadi
        MigrationPlanner.Options bulk = new MigrationPlanner.Options(T, properties.getMigrationGoLive(), true);
        MigrationPlanner.SgPlan old4 = plan(sg4, bulk);
        assertThat(old4.periods()).extracting(MigrationPlanner.PlannedPeriod::amount)
            .usingElementComparator(BigDecimal::compareTo)
            .containsExactly(new BigDecimal("1000001"), new BigDecimal("700000"), new BigDecimal("700000"));
        assertThat(old4.migrationAmount()).isEqualByComparingTo("1000001");
        assertThat(old4.storedBalance()).isEqualByComparingTo("700000");
        assertThat(old4.newDebt()).isEqualByComparingTo("700000");
        assertThat(old4.anomalies()).contains("A12", "A14");
        assertThat(plan(sg6, bulk).newDebt()).isEqualByComparingTo("700000");
        MigrationPlanner.SgPlan old46 = plan(sg46, bulk);
        assertThat(old46.migrationAmount()).isEqualByComparingTo("0");
        assertThat(old46.storedBalance()).isEqualByComparingTo("0");
        assertThat(old46.newDebt()).isEqualByComparingTo("1400000");
        assertThat(plan(sg47, bulk).newDebt()).isEqualByComparingTo("2800000");

        // Run qo'llangan, 4 tasi chetlatilgan → hold
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        MigrationPlanner.Report r = migration.dryRun(T, true);
        Long runId = migration.approve(T, true, r.reportHash(), "Egasi", null).getId();
        properties.setEnabled(false);
        migration.apply(runId, "APPLY-" + runId, heldOnes.stream().map(Ids::sg).toList(), false);
        properties.setEnabled(true);

        Map<Long, HeldEnrollmentService.HeldRow> rows = held.list().rows().stream()
            .collect(Collectors.toMap(HeldEnrollmentService.HeldRow::studentGroupId, x -> x));
        assertThat(rows).containsOnlyKeys(sg4.sg(), sg6.sg(), sg46.sg(), sg47.sg());

        HeldEnrollmentService.HeldRow r4 = rows.get(sg4.sg());
        assertThat(r4.periods()).extracting(MigrationPlanner.PlannedPeriod::start)
            .containsExactly(d("03.08.2026"), d("03.09.2026"), d("03.10.2026"));
        assertThat(r4.periods()).extracting(MigrationPlanner.PlannedPeriod::amount)
            .allSatisfy(a -> assertThat(a).isEqualByComparingTo("700000"));
        assertThat(r4.paymentsSum()).isEqualByComparingTo("2100000");
        assertThat(r4.paidNet()).isEqualByComparingTo("2100000");
        assertThat(r4.migrationAmount()).isEqualByComparingTo("1000001");   // v1 PERIOD_CHARGE to'liq neytrallanadi
        assertThat(r4.netAdjustment()).isEqualByComparingTo("399999");      // chegirma — kredit (ledgerda yo'q edi)
        assertThat(r4.balanceAfter()).isEqualByComparingTo("0");
        assertThat(r4.debtAfter()).isEqualByComparingTo("0");

        HeldEnrollmentService.HeldRow r6 = rows.get(sg6.sg());
        assertThat(r6.periods()).hasSize(2);
        assertThat(r6.debtAfter()).isEqualByComparingTo("0");

        HeldEnrollmentService.HeldRow r46 = rows.get(sg46.sg());
        assertThat(r46.periods()).extracting(MigrationPlanner.PlannedPeriod::start)
            .containsExactly(d("01.09.2026"), d("01.10.2026"));
        assertThat(r46.debtAfter()).isEqualByComparingTo("700000");
        assertThat(r46.debtSinceAfter()).isEqualTo(d("01.10.2026"));

        HeldEnrollmentService.HeldRow r47 = rows.get(sg47.sg());
        assertThat(r47.periods()).hasSize(5);
        assertThat(r47.charges()).isEqualByComparingTo("3500000");
        assertThat(r47.debtAfter()).isEqualByComparingTo("700000");
        assertThat(r47.debtSinceAfter()).isEqualTo(d("01.10.2026"));

        // Apply yozgani = reja; neytral juftlik yig'indisi 0 → debtSince v2 davridan
        HeldEnrollmentService.ApplyResult a4 = held.apply(sg4.sg(), "k-4", "Sof hisob", null, r4.planHash());
        assertThat(inTx(() -> sgRepo.findById(sg4.sg()).orElseThrow()).getBalance()).isEqualByComparingTo("0");
        assertThat(a4.after().debtAfter()).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT amount FROM balance_transactions WHERE student_group_id = ?"
            + " AND type = 'MANUAL_ADJUST' AND migration_run_id = ?", BigDecimal.class, sg4.sg(), runId))
            .isEqualByComparingTo("399999");

        HeldEnrollmentService.ApplyResult a47 = held.apply(sg47.sg(), "k-47", "Sof hisob", null, r47.planHash());
        assertThat(inTx(() -> sgRepo.findById(sg47.sg()).orElseThrow()).getBalance()).isEqualByComparingTo("-700000");
        assertThat(a47.after().debtSinceAfter()).isEqualTo(d("01.10.2026"));

        // Qaytarish → qayta qo'llash: sof hisob tuzatmasi ham qaytariladi, natija o'sha
        migration.revertSg(runId, sg47.sg(), "REVERT-" + runId + "-" + sg47.sg());
        assertThat(inTx(() -> sgRepo.findById(sg47.sg()).orElseThrow()).getBalance()).isEqualByComparingTo("0");
        assertThat(held.preview(sg47.sg(), null).debtAfter()).isEqualByComparingTo("700000");
    }

    /**
     * docs/ops/held-review.sql (read-only) — bulk migratsiya qilingan SG larda xuddi shu xato: o'sha 4 misol hold'siz
     * qo'llansa, so'rov ortiqcha qarzni va sababini ko'rsatadi; oddiy SG — OK.
     */
    @Test
    void heldReviewSql_findsOverchargedMigrated() throws Exception {
        Assumptions.assumeTrue(Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) c ->
            c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgres"))),
            "held-review.sql — PostgreSQL (pgtest)");
        clock.setDate(T);
        Ids ok = legacy();
        Ids sg4 = enrollment("03.08.2026");
        v1Payment(sg4, 1_400_000, 399_999, "03.08.2026", 1_000_001);
        v1Payment(sg4, 700_000, 0, "03.10.2026", 0);
        Ids sg6 = enrollment("29.08.2026");
        v1Payment(sg6, 1_400_000, 100_000, "29.08.2026", 1_300_000);
        Ids sg46 = enrollment("01.09.2026");
        v1Payment(sg46, 700_000, 700_000, "01.09.2026", 0);
        Ids sg47 = enrollment("01.06.2026");
        v1Payment(sg47, 2_800_000, 200_000, "01.06.2026", 2_600_000);
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        MigrationPlanner.Report r = migration.dryRun(T, true);
        Long runId = migration.approve(T, true, r.reportHash(), "Egasi", null).getId();
        properties.setEnabled(false);
        migration.apply(runId, "APPLY-" + runId, List.of(), false);
        properties.setEnabled(true);

        String sql = Files.readString(Path.of("docs/ops/held-review.sql"));
        Map<Long, Map<String, Object>> rows = jdbc.queryForList(sql).stream()
            .collect(Collectors.toMap(m -> ((Number) m.get("sg_id")).longValue(), m -> m));
        assertThat(rows).containsOnlyKeys(ok.sg(), sg4.sg(), sg6.sg(), sg46.sg(), sg47.sg());
        assertThat(rows.get(ok.sg())).containsEntry("reason", "OK");
        assertReview(rows.get(sg4.sg()), "-700000", "0", "-700000", "A14_MULTI");
        assertReview(rows.get(sg6.sg()), "-700000", "0", "-700000", "A14_MULTI");
        assertReview(rows.get(sg46.sg()), "-1400000", "-700000", "-700000", "DISCOUNT_UNCREDITED");
        assertReview(rows.get(sg47.sg()), "-2800000", "-700000", "-2100000", "A14_MULTI");
        // apply paytidagi target_old = hozirgi balans (keyin yozuv yo'q)
        assertThat((BigDecimal) rows.get(sg47.sg()).get("balance_now")).isEqualByComparingTo("-2800000");
    }

    private static void assertReview(Map<String, Object> row, String targetOld, String targetNet, String delta,
                                     String reason) {
        assertThat((BigDecimal) row.get("target_old")).isEqualByComparingTo(targetOld);
        assertThat((BigDecimal) row.get("target_net")).isEqualByComparingTo(targetNet);
        assertThat((BigDecimal) row.get("delta")).isEqualByComparingTo(delta);
        assertThat(row.get("reason")).isEqualTo(reason);
    }

    private static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOf(CodedException.class)
            .extracting(e -> ((CodedException) e).getCode()).isEqualTo(code);
    }

    @Test
    void list_preview_withAnchorOverride_writesNothing() {
        Ids h = appliedRunWithHeld();

        HeldEnrollmentService.HeldList list = held.list();
        assertThat(list.rows()).extracting(HeldEnrollmentService.HeldRow::studentGroupId).containsExactly(h.sg());
        HeldEnrollmentService.HeldRow row = list.rows().get(0);
        assertThat(row.paymentStartDate()).isEqualTo(d("20.09.2026"));
        assertThat(row.paymentsCount()).isEqualTo(1);
        assertThat(row.paymentsSum()).isEqualByComparingTo("700000");
        assertThat(row.periods()).extracting(MigrationPlanner.PlannedPeriod::start).containsExactly(d("20.09.2026"));
        assertThat(row.balanceAfter()).isEqualByComparingTo("0");
        assertThat(row.planHash()).hasSize(64);

        HeldEnrollmentService.HeldRow preview = held.preview(h.sg(), d("25.09.2026"));
        assertThat(preview.paymentStartDate()).isEqualTo(d("25.09.2026"));
        assertThat(preview.periods()).extracting(MigrationPlanner.PlannedPeriod::start).containsExactly(d("25.09.2026"));
        assertThat(preview.planHash()).isNotEqualTo(row.planHash());
        // hech narsa yozilmagan
        StudentGroup sg = inTx(() -> sgRepo.findById(h.sg()).orElseThrow());
        assertThat(sg.getPaymentStartDate()).isEqualTo(d("20.09.2026"));
        assertThat(sg.getBillingHold()).isTrue();
        assertThat(inTx(() -> periodRepo.findByStudentGroupIdOrderByPeriodStartAsc(h.sg()))).isEmpty();
    }

    @Test
    void apply_requiresKeyAndReason_planHash_thenAppliesWithNewAnchor_idempotentReplay() {
        Ids h = appliedRunWithHeld();
        String hash = held.preview(h.sg(), d("25.09.2026")).planHash();

        assertCode(() -> held.apply(h.sg(), " ", "Sabab", null, null), "billing.held.idempotencyKeyRequired");
        assertCode(() -> held.apply(h.sg(), "k-1", "  ", null, null), "billing.held.reasonRequired");
        assertCode(() -> held.apply(h.sg(), "k-1", "Sabab", d("25.09.2026"), "eskirgan-hash"), "billing.held.planChanged");
        assertThat(inTx(() -> sgRepo.findById(h.sg()).orElseThrow()).getPaymentStartDate())
            .as("409 da tranzaksiya qaytdi").isEqualTo(d("20.09.2026"));

        HeldEnrollmentService.ApplyResult res = held.apply(h.sg(), "k-1",
            "Egasi bilan kelishildi: langar 25.09", d("25.09.2026"), hash);
        assertThat(res.replay()).isFalse();
        assertThat(res.anchorBefore()).isEqualTo(d("20.09.2026"));
        assertThat(res.anchorAfter()).isEqualTo(d("25.09.2026"));
        StudentGroup after = inTx(() -> sgRepo.findById(h.sg()).orElseThrow());
        assertThat(after.getBillingHold()).isNull();
        assertThat(after.getBalance()).isEqualByComparingTo("0");
        assertThat(inTx(() -> periodRepo.findByStudentGroupIdOrderByPeriodStartAsc(h.sg())))
            .extracting(BillingPeriod::getPeriodStart).containsExactly(d("25.09.2026"));
        assertThat(inTx(() -> applicationRepo.findByStudentGroupIdOrderByIdDesc(h.sg()))).singleElement()
            .satisfies(a -> {
                assertThat(a.getReason()).isEqualTo("Egasi bilan kelishildi: langar 25.09");
                assertThat(a.getIdempotencyKey()).isEqualTo("k-1");
                assertThat(a.getPlanHash()).isEqualTo(hash);
                assertThat(a.getAppliedBy()).isEqualTo("test-super_admin");
            });

        // Takror (o'sha kalit) — o'sha natija, ikkinchi yozuv yo'q
        HeldEnrollmentService.ApplyResult again = held.apply(h.sg(), "k-1", "boshqa", null, null);
        assertThat(again.replay()).isTrue();
        assertThat(again.anchorAfter()).isEqualTo(d("25.09.2026"));
        assertThat(inTx(() -> applicationRepo.findByStudentGroupIdOrderByIdDesc(h.sg()))).hasSize(1);
        // Kalit boshqa SG uchun — 409; yangi kalit bilan qayta — hold emas
        assertCode(() -> held.apply(h.sg() + 1000, "k-1", "Sabab", null, null), "billing.held.idempotencyConflict");
        assertCode(() -> held.apply(h.sg(), "k-2", "Sabab", null, null), "migration.sgNotHeld");
    }

    @Test
    void endpoints_superAdminOnly_headerRequired() throws Exception {
        Ids h = appliedRunWithHeld();
        mvc.perform(get("/api/admin/billing/held").with(user("a").roles("ADMIN")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/billing/held").with(user("sa").roles("SUPER_ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.rows[0].studentGroupId").value(h.sg()));
        mvc.perform(post("/api/admin/billing/held/{id}/preview", h.sg()).with(user("sa").roles("SUPER_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"paymentStartDate\":\"2026-09-25\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.paymentStartDate").value("2026-09-25"));
        mvc.perform(post("/api/admin/billing/held/{id}/apply", h.sg()).with(user("sa").roles("SUPER_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("billing.held.idempotencyKeyRequired"));
    }
}
