package com.crm.payroll;

import com.crm.dto.request.PaymentRequest;
import com.crm.dto.request.PayrollPayDto;
import com.crm.dto.request.SalaryRuleRequest;
import com.crm.dto.request.TransferDto;
import com.crm.dto.response.CashTransactionDto;
import com.crm.dto.response.SalaryRuleResponse;
import com.crm.entity.CashTransaction;
import com.crm.entity.enums.CashDirection;
import com.crm.entity.enums.CashTransactionStatus;
import com.crm.entity.enums.CashTransactionType;
import com.crm.entity.enums.PaymentMethod;
import com.crm.entity.enums.UserRole;
import com.crm.service.CashRegisterService;
import com.crm.service.SalaryRuleService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Kassa yo'nalishi (direction/signedAmount), qoida ustma-ustligi (V57), bitta user uchun ensure-profile. */
class CashDirectionRuleOverlapProfileTest extends PayrollItBase {

    @Autowired CashRegisterService cashService;
    @Autowired SalaryRuleService ruleService;

    private List<CashTransactionDto> rows(Long registerId) {
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        return cashService.getTransactions(registerId, null, null, null, null, null, null, PageRequest.of(0, 50))
            .getContent();
    }

    private Long income(Ids ids, Long registerId, long amount, String date) {
        clock.setDate(d(date));
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        PaymentRequest r = new PaymentRequest();
        r.setStudentId(ids.student());
        r.setGroupId(ids.group());
        r.setAmount(BigDecimal.valueOf(amount));
        r.setCashRegisterId(registerId);
        r.setPaymentMethod(PaymentMethod.CASH);
        return payments.createPayment(r, null).getId();
    }

    // ── 1. direction / signedAmount / havolalar ─────────────────────────

    @Test
    void paymentAndItsCancellation_inThenOut_withLinks() {
        Long reg = fixtures.cashRegister(false);
        Ids s = monthly(null, 700_000, "15.09.2026");
        Long paymentId = income(s, reg, 700_000, "15.09.2026");
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        payments.cancelPayment(paymentId, "Xato kiritildi");

        List<CashTransactionDto> rows = rows(reg);
        CashTransactionDto in = rows.stream().filter(r -> r.getType() == CashTransactionType.INCOME).findFirst().orElseThrow();
        CashTransactionDto rev = rows.stream().filter(r -> r.getType() == CashTransactionType.REVERSAL).findFirst().orElseThrow();
        assertThat(in.getDirection()).isEqualTo(CashDirection.IN);
        assertThat(in.getSignedAmount()).isEqualByComparingTo("700000");
        assertThat(in.getPaymentId()).isEqualTo(paymentId);
        assertThat(rev.getDirection()).isEqualTo(CashDirection.OUT);
        assertThat(rev.getSignedAmount()).isEqualByComparingTo("-700000");
        assertThat(rev.getRelatedTxId()).isEqualTo(in.getId());
        assertThat(rev.getPaymentId()).isEqualTo(paymentId);
    }

    @Test
    void payrollPayAndCancel_outThenIn_withPayrollId() {
        Staff t = teacherStaff();
        teacherRule(t, 3_000_000, 0);
        Long id = draftFor(t.userId(), SEP).getId();
        payroll.approve(id, null);
        Long reg = fixtures.cashRegister(false);
        PayrollPayDto pay = new PayrollPayDto();
        pay.setCashRegisterId(reg);
        payroll.markAsPaid(id, pay, null);
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        payroll.cancel(id, "Xato hisob");

        List<CashTransactionDto> rows = rows(reg);
        assertThat(rows).extracting(CashTransactionDto::getPayrollId).containsOnly(id);
        assertThat(rows).filteredOn(r -> r.getType() == CashTransactionType.EXPENSE).singleElement()
            .satisfies(r -> {
                assertThat(r.getDirection()).isEqualTo(CashDirection.OUT);
                assertThat(r.getSignedAmount()).isEqualByComparingTo("-3000000");
            });
        assertThat(rows).filteredOn(r -> r.getType() == CashTransactionType.REVERSAL).singleElement()
            .satisfies(r -> {
                assertThat(r.getDirection()).isEqualTo(CashDirection.IN);
                assertThat(r.getSignedAmount()).isEqualByComparingTo("3000000");
            });
    }

    @Test
    void transfer_outRowOnSource_inRowOnTarget_legacyNameFallback_andExportSigned() throws Exception {
        Long a = fixtures.cashRegister(false);
        Long b = fixtures.cashRegister(false);
        income(monthly(null, 700_000, "15.09.2026"), a, 700_000, "15.09.2026");
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        TransferDto dto = new TransferDto();
        dto.setFromCashRegisterId(a);
        dto.setToCashRegisterId(b);
        dto.setAmount(BigDecimal.valueOf(200_000));
        dto.setPaymentMethod(PaymentMethod.CASH);
        cashService.transfer(dto);

        CashTransactionDto out = rows(a).stream().filter(r -> r.getType() == CashTransactionType.TRANSFER)
            .findFirst().orElseThrow();
        CashTransactionDto in = rows(b).stream().filter(r -> r.getType() == CashTransactionType.TRANSFER)
            .findFirst().orElseThrow();
        assertThat(out.getDirection()).isEqualTo(CashDirection.OUT);
        assertThat(out.getSignedAmount()).isEqualByComparingTo("-200000");
        assertThat(in.getDirection()).isEqualTo(CashDirection.IN);
        assertThat(in.getRelatedTxId()).isEqualTo(out.getId());

        // Eski kirim qatori (relatedTxId siz) — nom bo'yicha IN
        inTx(() -> {
            CashTransaction legacy = new CashTransaction();
            legacy.setCashRegister(registerRepo.findById(b).orElseThrow());
            legacy.setType(CashTransactionType.TRANSFER);
            legacy.setPaymentMethod(PaymentMethod.CASH);
            legacy.setAmount(BigDecimal.valueOf(50_000));
            legacy.setTransactionName("Ko'chirish (kirim)");
            legacy.setTransactionDate(d("01.09.2026"));
            legacy.setStatus(CashTransactionStatus.COMPLETED);
            return cashRepo.save(legacy);
        });
        assertThat(rows(b)).filteredOn(r -> r.getAmount().compareTo(BigDecimal.valueOf(50_000)) == 0)
            .singleElement().satisfies(r -> assertThat(r.getDirection()).isEqualTo(CashDirection.IN));

        byte[] xlsx = cashService.exportTransactions(a, null, null, null, null, null, null);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(3).getStringCellValue()).isEqualTo("Yo'nalish");
            boolean sawOut = false;
            boolean sawIn = false;
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                String dir = row.getCell(3).getStringCellValue();
                double amount = row.getCell(8).getNumericCellValue();
                if ("OUT".equals(dir)) {
                    sawOut = true;
                    assertThat(amount).isEqualTo(-200_000d);
                } else {
                    sawIn = true;
                    assertThat(amount).isEqualTo(700_000d);
                }
            }
            assertThat(sawOut).isTrue();
            assertThat(sawIn).isTrue();
        }
    }

    // ── 2. qoida ustma-ustligi ──────────────────────────────────────────

    private SalaryRuleRequest ruleReq(Long userId, long fixed, String from) {
        SalaryRuleRequest r = new SalaryRuleRequest();
        r.setRole(UserRole.TEACHER);
        r.setUserId(userId);
        r.setFixedSalary(BigDecimal.valueOf(fixed));
        r.setEffectiveFrom(LocalDate.parse(from));
        return r;
    }

    @Test
    void newRuleSameOrEarlierFrom_deactivatesUnusedPrevious_neverInvertedRange() {
        Staff t = teacherStaff();
        SalaryRuleResponse same1 = ruleService.create(ruleReq(t.userId(), 5_000_000, "2026-10-02"));
        SalaryRuleResponse same2 = ruleService.create(ruleReq(t.userId(), 6_000_000, "2026-10-02"));
        SalaryRuleResponse future = ruleService.create(ruleReq(t.userId(), 7_000_000, "2026-12-01"));
        SalaryRuleResponse earlier = ruleService.create(ruleReq(t.userId(), 8_000_000, "2026-11-01"));

        assertThat(ruleService.getById(same1.getId())).satisfies(r -> {
            assertThat(r.getIsActive()).isFalse();
            assertThat(r.getEffectiveTo()).isNull();
        });
        assertThat(ruleService.getById(future.getId()).getIsActive()).isFalse();   // 12.01 ≥ 11.01
        assertThat(ruleService.getById(same2.getId()).getEffectiveTo()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(ruleService.getById(earlier.getId()).getIsActive()).isTrue();
        for (SalaryRuleResponse r : ruleService.getAll()) {
            if (r.getEffectiveFrom() != null && r.getEffectiveTo() != null) {
                assertThat(r.getEffectiveTo()).isAfterOrEqualTo(r.getEffectiveFrom());
            }
        }
        assertThat(calculator.calculateForUser(t.userId(), OCT, YEAR).getBaseSalary()).isEqualByComparingTo("6000000");
        assertThat(calculator.calculateForUser(t.userId(), 12, YEAR).getBaseSalary()).isEqualByComparingTo("8000000");
    }

    @Test
    void newRuleOverlappingUsedRule_409_nothingChanged() {
        Staff t = teacherStaff();
        Long used = ruleService.create(ruleReq(t.userId(), 3_000_000, "2026-09-01")).getId();
        Long id = draftFor(t.userId(), SEP).getId();
        payroll.approve(id, null);

        assertCode(() -> ruleService.create(ruleReq(t.userId(), 4_000_000, "2026-09-01")), "salaryRule.overlapsUsed");
        assertCode(() -> ruleService.create(ruleReq(t.userId(), 4_000_000, "2026-08-01")), "salaryRule.overlapsUsed");
        assertThat(ruleService.getById(used).getIsActive()).isTrue();
        assertThat(ruleService.getById(used).getEffectiveTo()).isNull();
        assertThat(inTx(() -> ruleRepo.count())).isEqualTo(1);
        // Keyingi sanadan — odatdagidek: eskisi yopiladi
        ruleService.create(ruleReq(t.userId(), 4_000_000, "2026-10-01"));
        assertThat(ruleService.getById(used).getEffectiveTo()).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    void v57_fixesOverlappingAndInvertedRules_keepsUsedOnes_isIdempotent() {
        Assumptions.assumeTrue(isPostgres(), "V57 — PostgreSQL skripti (pgtest)");
        Staff t = teacherStaff();
        Staff u = teacherStaff();
        // Lokal bazadagi holat: bir doirada ikki faol qoida bir sanadan; yana teskari muddatli va ishlatilgan
        Long older = insertRule(null, 5_000_000, "2026-10-02", "2026-12-31");
        Long newer = insertRule(null, 6_000_000, "2026-10-02", "2026-12-31");
        Long inverted = insertRule(t.userId(), 1, "2026-10-02", "2026-10-01");
        Long usedOld = insertRule(u.userId(), 2, "2026-09-01", null);
        insertRule(u.userId(), 3, "2026-09-01", null);
        jdbc.update("""
            INSERT INTO payroll (uuid, user_id, month, year, status, salary_rule_id, created_at)
            VALUES (gen_random_uuid(), ?, 9, 2026, 'PAID', ?, now())
            """, u.userId(), usedOld);

        runScript("db/migration/V57__salary_rule_overlaps.sql");
        runScript("db/migration/V57__salary_rule_overlaps.sql");

        assertThat(active(older)).isFalse();
        assertThat(active(newer)).isTrue();
        assertThat(active(inverted)).isFalse();
        assertThat(jdbc.queryForObject("SELECT effective_to FROM salary_rules WHERE id = ?", LocalDate.class, inverted))
            .isNull();
        assertThat(active(usedOld)).as("ishlatilgan qoidaga tegilmaydi").isTrue();
    }

    private Long insertRule(Long userId, long fixed, String from, String to) {
        return jdbc.queryForObject("""
            INSERT INTO salary_rules (role, user_id, base_salary, per_student_fee, new_student_bonus, kpi_bonus,
                                      is_active, effective_from, effective_to, created_at, updated_at)
            VALUES ('TEACHER', ?, ?, 0, 0, 0, TRUE, ?, ?, now(), now()) RETURNING id
            """, Long.class, userId, BigDecimal.valueOf(fixed), LocalDate.parse(from),
            to != null ? LocalDate.parse(to) : null);
    }

    private boolean active(Long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT is_active FROM salary_rules WHERE id = ?", Boolean.class, id));
    }

    // ── 3. POST /api/teachers/{userId}/ensure-profile ───────────────────

    @Test
    void ensureProfile_createsThenExists_superAdminOnly() throws Exception {
        Long teacherUser = staffUser(UserRole.TEACHER);
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        mvc.perform(post("/api/teachers/" + teacherUser + "/ensure-profile")
                .with(user("test-super_admin").roles("SUPER_ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.createdCount").value(1))
            .andExpect(jsonPath("$.data.items[0].action").value("CREATED"))
            .andExpect(jsonPath("$.data.items[0].userId").value(teacherUser.intValue()))
            .andExpect(jsonPath("$.data.items[0].teacherId").isNumber());
        mvc.perform(post("/api/teachers/" + teacherUser + "/ensure-profile")
                .with(user("test-super_admin").roles("SUPER_ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.createdCount").value(0))
            .andExpect(jsonPath("$.data.items[0].action").value("EXISTS"));
        assertThat(inTx(() -> teacherRepo.findByUser_Id(teacherUser))).isPresent();

        mvc.perform(post("/api/teachers/" + teacherUser + "/ensure-profile").with(user("test-admin").roles("ADMIN")))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/teachers/999999/ensure-profile").with(user("test-super_admin").roles("SUPER_ADMIN")))
            .andExpect(status().isNotFound());
    }

    @Test
    void ensureProfile_skipsNonTeacher_andProfileOfAnotherUser_withReason() throws Exception {
        Long admin = staffUser(UserRole.ADMIN);
        Long teacherUser = staffUser(UserRole.TEACHER);
        Staff other = teacherStaff();
        jdbc.update("UPDATE users SET phone = '+998935555555' WHERE id = ?", teacherUser);
        jdbc.update("UPDATE teachers SET phone = '+998935555555' WHERE id = ?", other.teacherId());
        fixtures.loginAs(UserRole.SUPER_ADMIN);

        // T-07 (phase5-audit): SKIPPED endi 409 + sabab kodi; data — avvalgi items[0]
        mvc.perform(post("/api/teachers/" + admin + "/ensure-profile").with(user("test-super_admin").roles("SUPER_ADMIN")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("teacher.profile.roleNotTeacher"))
            .andExpect(jsonPath("$.data.action").value("SKIPPED"))
            .andExpect(jsonPath("$.data.reason").value("Roli TEACHER emas: ADMIN"));
        mvc.perform(post("/api/teachers/" + teacherUser + "/ensure-profile")
                .with(user("test-super_admin").roles("SUPER_ADMIN")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("teacher.profile.contactTaken"))
            .andExpect(jsonPath("$.data.action").value("SKIPPED"))
            .andExpect(jsonPath("$.data.teacherId").value(other.teacherId().intValue()));
        assertThat(inTx(() -> teacherRepo.findByUser_Id(teacherUser))).isEmpty();
    }

    private boolean isPostgres() {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) c ->
            c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres")));
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
