package com.crm.payroll;

import com.crm.dto.response.PayrollCalculationDetails;
import com.crm.dto.response.PayrollCalculationDetails.Line;
import com.crm.dto.response.SalaryCalculationDto;
import com.crm.entity.BalanceTransaction;
import com.crm.entity.BillingPeriod;
import com.crm.entity.SalaryRule;
import com.crm.entity.SalaryRuleKpiTier;
import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.BalanceTransactionType;
import com.crm.entity.enums.BillingPeriodStatus;
import com.crm.entity.enums.KpiMetric;
import com.crm.entity.enums.PaymentType;
import com.crm.entity.enums.StudentStatus;
import com.crm.entity.enums.UserRole;
import com.crm.repository.BalanceTransactionRepository;
import com.crm.repository.StudentGroupRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KPI metrikasi va "≥" pog'onalar (V88, payroll-v2 §14): SALES_HEAD oyligi = fiksa × stavka + markazga oyda kelgan
 * yangi o'quvchilar soni bo'yicha pog'ona (≥100 → 1 mln, ≥200 → 2 mln, ≥300 → 4 mln; eng yuqorisi, yig'ilmaydi).
 */
class PayrollKpiMetricTest extends PayrollItBase {

    private static final AtomicInteger PHONE = new AtomicInteger(5_000_000);

    @Autowired StudentGroupRepository sgRepo;
    @Autowired BalanceTransactionRepository txRepo;

    /** Pog'ona: chegara, bonus, "≥" mi. */
    private record T(int threshold, long bonus, boolean inclusive) {
    }

    private Long rule(Long userId, UserRole role, long fixed, String rate, KpiMetric metric, T... tiers) {
        return inTx(() -> {
            SalaryRule r = SalaryRule.builder()
                .role(role)
                .user(userRepo.findById(userId).orElseThrow())
                .fixedSalary(BigDecimal.valueOf(fixed))
                .employmentRate(new BigDecimal(rate))
                .perPayingStudent(BigDecimal.ZERO)
                .perNewStudent(BigDecimal.ZERO)
                .kpiMetric(metric)
                .effectiveFrom(LocalDate.of(2026, 1, 1))
                .isActive(true)
                .build();
            for (T t : tiers) {
                r.getKpiTiers().add(SalaryRuleKpiTier.builder().salaryRule(r).threshold(t.threshold())
                    .bonus(BigDecimal.valueOf(t.bonus())).inclusive(t.inclusive()).build());
            }
            return ruleRepo.save(r).getId();
        });
    }

    private Long salesHeadRule(Long userId) {
        return rule(userId, UserRole.SALES_HEAD, 5_000_000, "1", KpiMetric.NEW_STUDENTS,
            new T(100, 1_000_000, true), new T(200, 2_000_000, true), new T(300, 4_000_000, true));
    }

    /**
     * {@code n} ta o'quvchi, har biri {@code months} oylarining har biriga davr (01–oy oxiri, 100 000) va o'sha oyning
     * 1-kuni to'liq to'lovi — servislarsiz, bitta tranzaksiyada (tez). Birinchi oy — "yangi o'quvchi" oyi.
     */
    private void students(int n, Long groupId, Long attributedTo, int... months) {
        inTx(() -> {
            for (int i = 0; i < n; i++) {
                Student s = studentRepo.save(Student.builder()
                    .firstName("Yangi" + PHONE.incrementAndGet()).lastName("O'quvchi")
                    .phone("+99894" + String.format("%07d", PHONE.get()))
                    .status(StudentStatus.ACTIVE).attributedUserId(attributedTo)
                    .build());
                LocalDate first = LocalDate.of(YEAR, months[0], 1);
                StudentGroup sg = sgRepo.save(StudentGroup.builder()
                    .student(s).group(groupRepo.findById(groupId).orElseThrow())
                    .joinDate(first).paymentStartDate(first)
                    .paymentType(PaymentType.MONTHLY).isTrial(false).isActive(true).balance(BigDecimal.ZERO)
                    .build());
                for (int m : months) {
                    LocalDate start = LocalDate.of(YEAR, m, 1);
                    BillingPeriod p = periodRepo.save(BillingPeriod.builder()
                        .studentGroupId(sg.getId()).periodStart(start).periodEnd(start.plusMonths(1).minusDays(1))
                        .fee(BigDecimal.valueOf(100_000)).discountPercentage(BigDecimal.ZERO)
                        .amount(BigDecimal.valueOf(100_000)).status(BillingPeriodStatus.CHARGED)
                        .build());
                    BalanceTransaction charge = txRepo.save(BalanceTransaction.builder()
                        .student(s).studentGroup(sg).type(BalanceTransactionType.PERIOD_CHARGE)
                        .amount(BigDecimal.valueOf(-100_000)).balanceAfter(BigDecimal.valueOf(-100_000))
                        .effectiveDate(start).billingPeriodId(p.getId())
                        .build());
                    BalanceTransaction payment = txRepo.save(BalanceTransaction.builder()
                        .student(s).studentGroup(sg).type(BalanceTransactionType.PAYMENT)
                        .amount(BigDecimal.valueOf(100_000)).balanceAfter(BigDecimal.ZERO)
                        .effectiveDate(start)
                        .build());
                    p.setChargeTxId(charge.getId());
                    p.setPaidOn(start);
                    p.setPaidTxId(payment.getId());
                    periodRepo.save(p);
                }
            }
            return null;
        });
    }

    private SalaryCalculationDto calc(Long userId) {
        return calculator.calculateForUser(userId, OCT, YEAR);
    }

    private static Optional<Line> line(SalaryCalculationDto c, String code) {
        return c.getCalculationDetails().lines().stream().filter(l -> code.equals(l.code())).findFirst();
    }

    private static String kpiFormula(SalaryCalculationDto c) {
        return line(c, PayrollCalculationDetails.KPI).orElseThrow().formula();
    }

    // ── SALES_HEAD: markaz bo'yicha yangi o'quvchilar, "≥" pog'onalar ──

    @Test
    void salesHead_newStudentsAcrossCenter_highestInclusiveTier() {
        Long head = staffUser(UserRole.SALES_HEAD);
        Long otherSeller = staffUser(UserRole.SALES_MANAGER);
        salesHeadRule(head);
        Long g = fixtures.group(fixtures.course(100_000));
        clock.setDate(d("31.10.2026"));

        students(50, g, otherSeller, OCT);         // boshqa sotuvchiga biriktirilgan — markaz bo'yicha sanaladi
        students(49, g, null, OCT);
        students(30, g, null, SEP, OCT);           // eski o'quvchilar (birinchi davri sentyabrda) — yangi emas
        students(10, g, null, 11);                 // noyabrda kelgan — oktyabrga emas

        SalaryCalculationDto c99 = calc(head);
        assertThat(c99.getKpiAmount()).isEqualByComparingTo("0");
        assertThat(c99.getKpiApplied()).isFalse();
        assertThat(kpiFormula(c99)).isEqualTo("KPI: yangi o'quvchilar 99 < 100 → 0");
        assertThat(c99.getTotalAmount()).isEqualByComparingTo("5000000");
        assertThat(c99.getTotalActiveStudents()).isNull();            // faol o'quvchi metrikasi emas

        students(100, g, null, OCT);
        SalaryCalculationDto c199 = calc(head);
        assertThat(c199.getKpiAmount()).isEqualByComparingTo("1000000");
        assertThat(kpiFormula(c199)).isEqualTo("KPI: yangi o'quvchilar 199 ≥ 100 → 1 000 000");
        assertThat(c199.getTotalAmount()).isEqualByComparingTo("6000000");

        students(1, g, null, OCT);
        SalaryCalculationDto c200 = calc(head);
        assertThat(c200.getKpiAmount()).isEqualByComparingTo("2000000");   // chegarada "≥" — bajarilgan
        assertThat(kpiFormula(c200)).isEqualTo("KPI: yangi o'quvchilar 200 ≥ 200 → 2 000 000");
        PayrollCalculationDetails.Kpi kpi = c200.getCalculationDetails().items().kpi();
        assertThat(kpi.metric()).isEqualTo("NEW_STUDENTS");
        assertThat(kpi.actual()).isEqualTo(200);
        assertThat(kpi.threshold()).isEqualTo(200);
        assertThat(kpi.tiers()).hasSize(3).allMatch(t -> Boolean.TRUE.equals(t.inclusive()));
        assertThat(c200.getCalculationDetails().rule().kpiMetric()).isEqualTo("NEW_STUDENTS");

        students(150, g, null, OCT);
        SalaryCalculationDto c350 = calc(head);
        assertThat(c350.getKpiAmount()).isEqualByComparingTo("4000000");   // yig'ilmaydi: 1 + 2 + 4 emas
        assertThat(kpiFormula(c350)).isEqualTo("KPI: yangi o'quvchilar 350 ≥ 300 → 4 000 000");
        assertThat(c350.getTotalAmount()).isEqualByComparingTo("9000000");
    }

    @Test
    void salesHead_rateAppliesToFixedOnly() {
        Long head = staffUser(UserRole.SALES_HEAD);
        rule(head, UserRole.SALES_HEAD, 5_000_000, "0.5", KpiMetric.NEW_STUDENTS, new T(1, 1_000_000, true));
        students(1, fixtures.group(fixtures.course(100_000)), null, OCT);
        clock.setDate(d("31.10.2026"));
        SalaryCalculationDto c = calc(head);
        assertThat(c.getBaseSalary()).isEqualByComparingTo("2500000");
        assertThat(c.getTotalAmount()).isEqualByComparingTo("3500000");
    }

    @Test
    void inclusiveVsExclusive_boundary_bothMetrics() {
        Long g = fixtures.group(fixtures.course(100_000));
        students(3, g, null, OCT);
        clock.setDate(d("31.10.2026"));

        Long headGt = staffUser(UserRole.SALES_HEAD);                // "> 3" — 3 da yo'q
        rule(headGt, UserRole.SALES_HEAD, 0, "1", KpiMetric.NEW_STUDENTS, new T(3, 500_000, false));
        assertThat(calc(headGt).getKpiAmount()).isEqualByComparingTo("0");
        assertThat(kpiFormula(calc(headGt))).isEqualTo("KPI: yangi o'quvchilar 3 ≤ 3 → 0");

        Long headGe = staffUser(UserRole.SALES_HEAD);                // "≥ 3" — 3 da bor
        rule(headGe, UserRole.SALES_HEAD, 0, "1", KpiMetric.NEW_STUDENTS, new T(3, 500_000, true));
        assertThat(calc(headGe).getKpiAmount()).isEqualByComparingTo("500000");

        Long adminGt = staffUser(UserRole.ADMIN);                    // faol 3: "> 3" yo'q, "≥ 3" bor
        rule(adminGt, UserRole.ADMIN, 0, "1", KpiMetric.ACTIVE_STUDENTS, new T(3, 700_000, false));
        SalaryCalculationDto gt = calc(adminGt);
        assertThat(gt.getKpiAmount()).isEqualByComparingTo("0");
        assertThat(kpiFormula(gt)).isEqualTo("KPI: 3 ≤ 3 → 0");     // admin ko'rinishi o'zgarmagan
        assertThat(gt.getTotalActiveStudents()).isEqualTo(3);

        Long adminGe = staffUser(UserRole.ADMIN);
        rule(adminGe, UserRole.ADMIN, 0, "1", KpiMetric.ACTIVE_STUDENTS, new T(3, 700_000, true));
        SalaryCalculationDto ge = calc(adminGe);
        assertThat(ge.getKpiAmount()).isEqualByComparingTo("700000");
        assertThat(kpiFormula(ge)).isEqualTo("KPI: 3 ≥ 3 → 700 000");
        assertThat(ge.getCalculationDetails().items().kpi().metric()).isEqualTo("ACTIVE_STUDENTS");

        Long adminMiss = staffUser(UserRole.ADMIN);                  // "≥ 4" — 3 da yo'q, "<" ko'rinishi
        rule(adminMiss, UserRole.ADMIN, 0, "1", KpiMetric.ACTIVE_STUDENTS, new T(4, 700_000, true));
        assertThat(kpiFormula(calc(adminMiss))).isEqualTo("KPI: 3 < 4 → 0");
    }

    @Test
    void existingRules_unchanged_adminActiveDefault_salesWithoutTiersNoKpi() {
        Long g = fixtures.group(fixtures.course(100_000));
        students(5, g, null, OCT);
        clock.setDate(d("31.10.2026"));

        // Admin qoidasi metrikasiz (V88 dan oldingi kabi) — faol o'quvchilar, "> threshold"
        Long admin = staffUser(UserRole.ADMIN);
        Long adminRule = inTx(() -> {
            SalaryRule r = SalaryRule.builder().role(UserRole.ADMIN).user(userRepo.findById(admin).orElseThrow())
                .fixedSalary(BigDecimal.valueOf(1_000_000)).kpiMetric(null)
                .effectiveFrom(LocalDate.of(2026, 1, 1)).isActive(true).build();
            r.getKpiTiers().add(SalaryRuleKpiTier.builder().salaryRule(r).threshold(4)
                .bonus(BigDecimal.valueOf(300_000)).inclusive(null).build());
            return ruleRepo.save(r).getId();
        });
        assertThat(jdbc.queryForObject("SELECT kpi_metric FROM salary_rules WHERE id = ?", String.class, adminRule))
            .isEqualTo("ACTIVE_STUDENTS");
        assertThat(jdbc.queryForObject("SELECT inclusive FROM salary_rule_kpi_tiers WHERE salary_rule_id = ?",
            Boolean.class, adminRule)).isFalse();
        SalaryCalculationDto a = calc(admin);
        assertThat(a.getKpiAmount()).isEqualByComparingTo("300000");
        assertThat(kpiFormula(a)).isEqualTo("KPI: 5 > 4 → 300 000");
        assertThat(a.getTotalActiveStudents()).isEqualTo(5);

        // SALES_HEAD pog'onasiz va SALES_MANAGER — KPI qatori yo'q (avvalgidek)
        Long head = staffUser(UserRole.SALES_HEAD);
        rule(head, UserRole.SALES_HEAD, 2_000_000, "1", KpiMetric.ACTIVE_STUDENTS);
        SalaryCalculationDto h = calc(head);
        assertThat(line(h, PayrollCalculationDetails.KPI)).isEmpty();
        assertThat(h.getCalculationDetails().items().kpi()).isNull();
        assertThat(h.getTotalAmount()).isEqualByComparingTo("2000000");
        Long seller = staffUser(UserRole.SALES_MANAGER);
        rule(seller, UserRole.SALES_MANAGER, 2_000_000, "1", KpiMetric.ACTIVE_STUDENTS);
        assertThat(line(calc(seller), PayrollCalculationDetails.KPI)).isEmpty();

        // Xom INSERT kpi_metric / inclusive siz — Hibernate sxemasida ham DEFAULT (V88 bilan bir xil)
        jdbc.update("""
            INSERT INTO salary_rules (role, user_id, base_salary, per_student_fee, new_student_bonus, kpi_bonus,
                                      is_active, created_at, updated_at)
            VALUES ('ADMIN', ?, 1, 0, 0, 0, TRUE, now(), now())
            """, admin);
        Long raw = jdbc.queryForObject("SELECT id FROM salary_rules WHERE user_id = ? AND id <> ?", Long.class,
            admin, adminRule);
        assertThat(jdbc.queryForObject("SELECT kpi_metric FROM salary_rules WHERE id = ?", String.class, raw))
            .isEqualTo("ACTIVE_STUDENTS");
        jdbc.update("INSERT INTO salary_rule_kpi_tiers (salary_rule_id, threshold, bonus) VALUES (?, 10, 5)", raw);
        assertThat(jdbc.queryForObject("SELECT inclusive FROM salary_rule_kpi_tiers WHERE salary_rule_id = ?",
            Boolean.class, raw)).isFalse();
    }

    // ── V88: idempotent, CHECK "= OR" ko'rinishida — EnumCheckConstraintCleaner o'chirmaydi (faqat PostgreSQL) ──

    @Test
    void v88_idempotent_checkSurvivesEnumCleaner() {
        Assumptions.assumeTrue(isPostgres(), "V88 — PostgreSQL skripti (pgtest)");
        String def = jdbc.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint"
            + " WHERE conname = 'salary_rules_kpi_metric_chk'", String.class);   // startup cleaner'dan keyin ham bor
        assertThat(def).doesNotContain("ANY").contains("NEW_STUDENTS");

        Long admin = staffUser(UserRole.ADMIN);
        Long ruleId = rule(admin, UserRole.ADMIN, 1, "1", KpiMetric.ACTIVE_STUDENTS, new T(5, 10, false));
        runScript("db/migration/V88__salary_rule_kpi_metric_inclusive.sql");
        runScript("db/migration/V88__salary_rule_kpi_metric_inclusive.sql");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_constraint WHERE conname = 'salary_rules_kpi_metric_chk'",
            Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT kpi_metric FROM salary_rules WHERE id = ?", String.class, ruleId))
            .isEqualTo("ACTIVE_STUDENTS");
        assertThat(jdbc.queryForObject("SELECT inclusive FROM salary_rule_kpi_tiers WHERE salary_rule_id = ?",
            Boolean.class, ruleId)).isFalse();
        assertThat(jdbc.queryForObject("""
            SELECT column_default FROM information_schema.columns
             WHERE table_name = 'salary_rule_kpi_tiers' AND column_name = 'inclusive'
            """, String.class)).isEqualTo("false");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbc.update("UPDATE salary_rules SET kpi_metric = 'FOO' WHERE id = ?", ruleId))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    // ── Qoida API ──

    @Test
    void salaryRuleApi_kpiMetric_inclusive_validation() throws Exception {
        Long head = staffUser(UserRole.SALES_HEAD);
        mvc.perform(post("/api/salary-rules").with(user("sa").roles("SUPER_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"role":"SALES_HEAD","userId":%d,"fixedSalary":5000000,"employmentRate":1,
                     "kpiMetric":"NEW_STUDENTS",
                     "kpiTiers":[{"threshold":300,"bonus":4000000,"inclusive":true},
                                 {"threshold":100,"bonus":1000000,"inclusive":true},
                                 {"threshold":200,"bonus":2000000,"inclusive":true}],
                     "effectiveFrom":"2026-11-01"}
                    """.formatted(head)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.kpiMetric").value("NEW_STUDENTS"))
            .andExpect(jsonPath("$.data.kpiTiers[0].threshold").value(100))
            .andExpect(jsonPath("$.data.kpiTiers[0].inclusive").value(true))
            .andExpect(jsonPath("$.data.kpiTiers[2].bonus").value(4000000))
            .andExpect(jsonPath("$.data.kpiThreshold").doesNotExist())       // eski ustunlar faqat ADMIN + faol uchun
            .andExpect(jsonPath("$.data.kpiBonus").value(0));

        Long admin = staffUser(UserRole.ADMIN);                              // metrikasiz — ACTIVE_STUDENTS
        mvc.perform(post("/api/salary-rules").with(user("sa").roles("SUPER_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"role":"ADMIN","userId":%d,"fixedSalary":1,
                     "kpiTiers":[{"threshold":500,"bonus":1000000,"inclusive":true},{"threshold":600,"bonus":2}]}
                    """.formatted(admin)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.kpiMetric").value("ACTIVE_STUDENTS"))
            .andExpect(jsonPath("$.data.kpiTiers[0].inclusive").value(true))
            .andExpect(jsonPath("$.data.kpiTiers[1].inclusive").value(false))  // berilmasa — ">"
            .andExpect(jsonPath("$.data.kpiThreshold").value(500));          // "≥ 500" — eski ko'rinish o'zi

        for (String body : List.of(
                "{\"role\":\"SALES_MANAGER\",\"kpiTiers\":[{\"threshold\":1,\"bonus\":1}]}",
                "{\"role\":\"TEACHER\",\"kpiTiers\":[{\"threshold\":1,\"bonus\":1,\"inclusive\":true}]}",
                "{\"role\":\"TEACHER\",\"kpiMetric\":\"NEW_STUDENTS\"}",
                "{\"role\":\"SALES_MANAGER\",\"kpiMetric\":\"NEW_STUDENTS\"}",
                "{\"role\":\"SALES_HEAD\",\"kpiThreshold\":100,\"kpiBonus\":1}")) {   // eski maydonlar — faqat ADMIN
            mvc.perform(post("/api/salary-rules").with(user("sa").roles("SUPER_ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("salaryRule.field.notApplicable"));
        }
        mvc.perform(post("/api/salary-rules").with(user("sa").roles("SUPER_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"SALES_HEAD\",\"kpiTiers\":[{\"threshold\":5,\"bonus\":1,\"inclusive\":true},"
                    + "{\"threshold\":5,\"bonus\":2}]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("salaryRule.kpiTiers.invalid"));
        mvc.perform(post("/api/salary-rules").with(user("sa").roles("SUPER_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"SALES_HEAD\",\"kpiMetric\":\"PAYMENTS\"}"))
            .andExpect(status().isBadRequest());
    }

    private boolean isPostgres() {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) c ->
            c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgres")));
    }

    private void runScript(String path) {
        jdbc.execute((ConnectionCallback<Void>) c -> {
            ScriptUtils.executeSqlScript(c, new EncodedResource(new ClassPathResource(path)),
                false, false, ScriptUtils.DEFAULT_COMMENT_PREFIX, ScriptUtils.EOF_STATEMENT_SEPARATOR,
                ScriptUtils.DEFAULT_BLOCK_COMMENT_START_DELIMITER, ScriptUtils.DEFAULT_BLOCK_COMMENT_END_DELIMITER);
            return null;
        });
    }
}
