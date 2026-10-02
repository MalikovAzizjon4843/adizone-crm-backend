package com.crm.payroll;

import com.crm.billing.BonusLedgerService;
import com.crm.config.PayrollProperties;
import com.crm.dto.request.BonusPenaltyCreateDto;
import com.crm.dto.request.PaymentRequest;
import com.crm.dto.request.PayrollPayDto;
import com.crm.dto.request.SalaryRuleRequest;
import com.crm.dto.response.BonusPenaltyDto;
import com.crm.dto.response.FinanceReportResponse;
import com.crm.dto.response.PayrollCalculationDetails;
import com.crm.dto.response.PayrollResponse;
import com.crm.dto.response.SalaryCalculationDto;
import com.crm.dto.response.SalaryRuleResponse;
import com.crm.entity.BillingMigrationRun;
import com.crm.entity.BonusPenalty;
import com.crm.entity.enums.AttendanceStatus;
import com.crm.entity.enums.BonusPenaltyKind;
import com.crm.entity.enums.BonusPenaltyStatus;
import com.crm.entity.enums.BonusTargetType;
import com.crm.entity.enums.PaymentMethod;
import com.crm.entity.enums.PayrollStatus;
import com.crm.entity.enums.UserRole;
import com.crm.repository.BillingMigrationRunRepository;
import com.crm.service.BonusPenaltyService;
import com.crm.service.FinanceService;
import com.crm.service.SalaryRuleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Payroll v2 — §11 egasi qarorlari (1, 2, 3, 4, 5, 7, 8). */
class PayrollOwnerDecisionsTest extends PayrollItBase {

    @Autowired PayrollProperties properties;
    @Autowired BillingMigrationRunRepository runRepo;
    @Autowired SalaryRuleService ruleService;
    @Autowired BonusPenaltyService bonusService;
    @Autowired BonusLedgerService bonusLedger;
    @Autowired FinanceService finance;

    @AfterEach
    void resetProperties() {
        properties.setCutoverDate(null);
        properties.setCountDiscountCovered(false);
    }

    private SalaryCalculationDto calc(Long userId, int month) {
        return calculator.calculateForUser(userId, month, YEAR);
    }

    private Staff paidTeacher() {
        Staff t = teacherStaff();
        teacherRule(t, 3_000_000, 100_000);
        pay(monthly(t.teacherId(), 700_000, "15.09.2026"), 700_000, "15.09.2026");
        return t;
    }

    // ── #1: cutover ─────────────────────────────────────────────────────

    @Test
    void cutover_fromProperty_rejectsEarlierMonths_cutoverMonthEstimated() {
        properties.setCutoverDate(LocalDate.of(2026, 9, 10));
        Staff t = paidTeacher();

        assertCode(() -> calc(t.userId(), 8), "payroll.beforeCutover");
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        assertCode(() -> payroll.generatePayroll(8, YEAR, false), "payroll.beforeCutover");
        assertCode(() -> payroll.previewCalculate(8, YEAR), "payroll.beforeCutover");

        assertThat(calc(t.userId(), SEP).getCalculationDetails().estimated()).isTrue();
        assertThat(calc(t.userId(), OCT).getCalculationDetails().estimated()).isFalse();
        Long id = draftFor(t.userId(), SEP).getId();
        assertThat(calculator.fromJson(payroll(id).getCalculationDetails()).estimated()).isTrue();
    }

    @Test
    void cutover_fromAppliedMigrationRun_whenPropertyUnset_failedRunsIgnored() {
        run(BillingMigrationRun.Status.FAILED, "01.07.2026");
        run(BillingMigrationRun.Status.APPLIED, "18.09.2026");
        Staff t = paidTeacher();

        assertCode(() -> calc(t.userId(), 8), "payroll.beforeCutover");
        assertThat(calc(t.userId(), SEP).getCalculationDetails().estimated()).isTrue();
        assertThat(calc(t.userId(), SEP).getTotalAmount()).isEqualByComparingTo("3100000");
    }

    private void run(BillingMigrationRun.Status status, String cutover) {
        inTx(() -> runRepo.save(BillingMigrationRun.builder()
            .status(status).cutoverDate(d(cutover)).goLiveDate(d(cutover))
            .reportHash("h").approvedByOwner("Egasi").approvedAt(LocalDateTime.now())
            .build()));
    }

    // ── #2: PER_LESSON — o'qituvchi almashsa darslar ulushida ───────────

    @Test
    void perLesson_teacherChange_splitsUnitByLessonShare_moneyRounded() {
        Staff a = teacherStaff();
        Staff b = teacherStaff();
        teacherRule(a, 0, 100_000);
        teacherRule(b, 0, 100_000);
        Ids s = perLesson(a.teacherId(), 80_000, "01.09.2026");
        clock.setDate(d("20.09.2026"));
        attend(s, "08.09.2026", AttendanceStatus.PRESENT);
        attend(s, "10.09.2026", AttendanceStatus.PRESENT);
        changeTeacher(s.group(), b.teacherId());
        attend(s, "15.09.2026", AttendanceStatus.PRESENT);

        SalaryCalculationDto ca = calc(a.userId(), SEP);
        SalaryCalculationDto cb = calc(b.userId(), SEP);
        assertThat(ca.getPaidStudentUnits()).isEqualByComparingTo("0.6667");
        assertThat(ca.getPerStudentAmount()).isEqualByComparingTo("66667");
        assertThat(cb.getPaidStudentUnits()).isEqualByComparingTo("0.3333");
        assertThat(cb.getPerStudentAmount()).isEqualByComparingTo("33333");
        assertThat(ca.getCalculationDetails().items().lessonEnrollments()).singleElement().satisfies(l -> {
            assertThat(l.lessons()).isEqualTo(2);
            assertThat(l.totalLessons()).isEqualTo(3);
            assertThat(l.share()).isEqualByComparingTo("0.6667");
        });
        assertThat(ca.getCalculationDetails().lines().get(1).count()).isEqualByComparingTo("0.6667");
    }

    @Test
    void periodsAndLessonShares_combine_unitsStoredOnPayroll() {
        Staff a = teacherStaff();
        Staff b = teacherStaff();
        teacherRule(a, 0, 100_000);
        teacherRule(b, 0, 100_000);
        pay(monthly(a.teacherId(), 700_000, "15.09.2026"), 700_000, "15.09.2026");
        Ids s = perLesson(a.teacherId(), 80_000, "01.09.2026");
        clock.setDate(d("20.09.2026"));
        attend(s, "08.09.2026", AttendanceStatus.PRESENT);
        attend(s, "10.09.2026", AttendanceStatus.PRESENT);
        changeTeacher(s.group(), b.teacherId());
        attend(s, "17.09.2026", AttendanceStatus.PRESENT);

        PayrollResponse draft = draftFor(a.userId(), SEP);
        assertThat(draft.getPaidStudentCount()).isEqualTo(2);
        assertThat(draft.getPaidStudentUnits()).isEqualByComparingTo("1.6667");
        assertThat(draft.getNetSalary()).isEqualByComparingTo("166667");
    }

    // ── #3: effectiveTo, avtomatik yopish, ishlatilgan qoida 409 ─────────

    private SalaryRuleRequest ruleRequest(Long userId, UserRole role, long fixed, String from) {
        SalaryRuleRequest r = new SalaryRuleRequest();
        r.setRole(role);
        r.setUserId(userId);
        r.setFixedSalary(BigDecimal.valueOf(fixed));
        r.setEffectiveFrom(from != null ? LocalDate.parse(from) : null);
        return r;
    }

    @Test
    void newRule_closesPreviousRuleOfSameScope_dayBefore() {
        Staff t = teacherStaff();
        SalaryRuleResponse roleRule = ruleService.create(ruleRequest(null, UserRole.TEACHER, 1_000_000, "2026-01-01"));
        SalaryRuleResponse old = ruleService.create(ruleRequest(t.userId(), UserRole.TEACHER, 3_000_000, "2026-01-01"));
        SalaryRuleResponse fresh = ruleService.create(ruleRequest(t.userId(), UserRole.TEACHER, 3_500_000, "2026-10-01"));

        assertThat(ruleService.getById(old.getId()).getEffectiveTo()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(ruleService.getById(fresh.getId()).getEffectiveTo()).isNull();
        assertThat(ruleService.getById(roleRule.getId()).getEffectiveTo()).isNull();   // boshqa doira
        assertThat(calc(t.userId(), SEP).getBaseSalary()).isEqualByComparingTo("3000000");
        assertThat(calc(t.userId(), OCT).getBaseSalary()).isEqualByComparingTo("3500000");
        assertThat(calc(t.userId(), OCT).getCalculationDetails().rule().id()).isEqualTo(fresh.getId());
    }

    @Test
    void editingRuleUsedInApprovedPayroll_conflicts_draftUseIsEditable() {
        Staff t = teacherStaff();
        Long ruleId = ruleService.create(ruleRequest(t.userId(), UserRole.TEACHER, 3_000_000, "2026-01-01")).getId();
        Long id = draftFor(t.userId(), SEP).getId();
        assertThat(payroll(id).getSalaryRuleId()).isEqualTo(ruleId);

        ruleService.update(ruleId, ruleRequest(t.userId(), UserRole.TEACHER, 3_200_000, "2026-01-01"));
        payroll.recalculate(id);
        payroll.approve(id, null);
        assertCode(() -> ruleService.update(ruleId, ruleRequest(t.userId(), UserRole.TEACHER, 9_000_000, "2026-01-01")),
            "salaryRule.inUse");
        assertThat(inTx(() -> ruleRepo.findById(ruleId).orElseThrow()).getFixedSalary()).isEqualByComparingTo("3200000");
    }

    @Test
    void effectiveTo_endsRule_andInvalidRangeRejected() {
        Staff t = teacherStaff();
        SalaryRuleRequest r = ruleRequest(t.userId(), UserRole.TEACHER, 3_000_000, "2026-01-01");
        r.setEffectiveTo(LocalDate.of(2026, 8, 31));
        ruleService.create(r);
        assertThat(calc(t.userId(), 8).getCalculable()).isTrue();
        assertThat(calc(t.userId(), SEP).getCalculable()).isFalse();
        assertThat(calc(t.userId(), SEP).getMessage()).isEqualTo("Oylik qoidasi topilmadi");

        SalaryRuleRequest bad = ruleRequest(t.userId(), UserRole.TEACHER, 1, "2026-10-01");
        bad.setEffectiveTo(LocalDate.of(2026, 9, 1));
        assertCode(() -> ruleService.create(bad), "salaryRule.effectiveRange.invalid");
    }

    // ── #4: approve {expectedNetSalary} ─────────────────────────────────

    @Test
    void approve_withMatchingExpectedNet_succeeds() {
        Staff t = paidTeacher();
        PayrollResponse draft = draftFor(t.userId(), SEP);
        PayrollResponse approved = payroll.approve(draft.getId(), new BigDecimal("3100000.00"));
        assertThat(approved.getStatus()).isEqualTo(PayrollStatus.APPROVED);
    }

    @Test
    void approve_withStaleExpectedNet_409WithNewNet_nothingWritten() throws Exception {
        Staff t = paidTeacher();
        PayrollResponse draft = draftFor(t.userId(), SEP);
        Long bonus = teacherBonus(t.teacherId(), BonusPenaltyKind.BONUS, 250_000, "20.09.2026");   // DRAFT dan keyin

        mvc.perform(post("/api/payroll/" + draft.getId() + "/approve")
                .with(user("test-super_admin").roles("SUPER_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedNetSalary\": 3100000}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("payroll.netChanged"))
            .andExpect(jsonPath("$.data.netSalary").value(is(3350000.0), Double.class))
            .andExpect(jsonPath("$.data.expectedNetSalary").value(is(3100000.0), Double.class));

        assertThat(payroll(draft.getId()).getStatus()).isEqualTo(PayrollStatus.DRAFT);
        assertThat(bonus(bonus).getStatus()).isEqualTo(BonusPenaltyStatus.PENDING);
        assertThat(payroll.approve(draft.getId(), null).getNetSalary()).isEqualByComparingTo("3350000");
    }

    // ── #5: STAFF bonus/jarima ──────────────────────────────────────────

    private Long staffBonus(Long userId, BonusPenaltyKind kind, long amount, String date) {
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        BonusPenaltyCreateDto dto = new BonusPenaltyCreateDto();
        dto.setKind(kind);
        dto.setTargetType(BonusTargetType.STAFF);
        dto.setUserId(userId);
        dto.setAmount(BigDecimal.valueOf(amount));
        dto.setEffectiveDate(d(date));
        dto.setReason("Reja bajarildi");
        return bonusService.create(dto).getId();
    }

    @Test
    void staffBonus_pendingInDraft_appliedOnApprove_pendingAgainOnCancel() {
        Long sales = staffUser(UserRole.SALES_MANAGER);
        rule(sales, UserRole.SALES_MANAGER, 2_000_000, 0, 150_000, null, 0);
        Long b = staffBonus(sales, BonusPenaltyKind.BONUS, 300_000, "10.09.2026");
        Long p = staffBonus(sales, BonusPenaltyKind.PENALTY, 100_000, "12.09.2026");

        PayrollResponse draft = draftFor(sales, SEP);
        assertThat(draft.getBonusPenaltyAdjustment()).isEqualByComparingTo("200000");
        assertThat(draft.getNetSalary()).isEqualByComparingTo("2200000");
        assertThat(bonus(b).getStatus()).isEqualTo(BonusPenaltyStatus.PENDING);

        payroll.approve(draft.getId(), null);
        assertThat(bonus(b).getStatus()).isEqualTo(BonusPenaltyStatus.APPLIED);
        assertThat(bonus(p).getAppliedToPayrollId()).isEqualTo(draft.getId());
        assertThat(calculator.fromJson(payroll(draft.getId()).getCalculationDetails()).lines())
            .filteredOn(l -> l.code().equals(PayrollCalculationDetails.BONUS))
            .singleElement().satisfies(l -> assertThat(l.status()).isEqualTo("APPLIED"));

        payroll.markAsPaid(draft.getId(), null, null);
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        payroll.cancel(draft.getId(), "Qayta ko'rib chiqiladi");
        assertThat(bonus(b).getStatus()).isEqualTo(BonusPenaltyStatus.PENDING);
        assertThat(bonus(p).getAppliedToPayrollId()).isNull();
    }

    @Test
    void staffBonus_requiresAdminOrSalesUser() {
        Long admin = staffUser(UserRole.ADMIN);
        Staff t = teacherStaff();
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        BonusPenaltyCreateDto noUser = new BonusPenaltyCreateDto();
        noUser.setKind(BonusPenaltyKind.BONUS);
        noUser.setTargetType(BonusTargetType.STAFF);
        noUser.setAmount(BigDecimal.TEN);
        assertCode(() -> bonusService.create(noUser), "bonus.staff.userRequired");
        noUser.setUserId(t.userId());
        assertCode(() -> bonusService.create(noUser), "bonus.staff.roleInvalid");

        BonusPenaltyDto ok = bonusService.getById(staffBonus(admin, BonusPenaltyKind.BONUS, 50_000, "10.09.2026"));
        assertThat(ok.getUserId()).isEqualTo(admin);
        assertThat(ok.getTargetName()).isEqualTo(ok.getUserName());
        assertThat(bonusService.previewForStaff(admin, d("30.09.2026")).getNet()).isEqualByComparingTo("50000");
    }

    // ── #7: faqat real PAYMENT ishtirok etgan davr ──────────────────────

    private Long studentBonus(Ids ids, long amount, String date) {
        clock.setDate(d(date));
        BonusPenalty bp = new BonusPenalty();
        bp.setKind(BonusPenaltyKind.BONUS);
        bp.setTargetType(BonusTargetType.STUDENT);
        bp.setStudent(studentRepo.findById(ids.student()).orElseThrow());
        bp.setStudentGroupId(ids.sg());
        bp.setAmount(BigDecimal.valueOf(amount));
        bp.setEffectiveDate(d(date));
        bp.setStatus(BonusPenaltyStatus.PENDING);
        Long id = inTx(() -> bonusRepo.save(bp).getId());
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        bonusLedger.apply(id, ids.group());
        return id;
    }

    @Test
    void periodClosedOnlyByStudentBonus_notCounted_unlessSettingEnabled() {
        Staff t = teacherStaff();
        teacherRule(t, 0, 100_000);
        Ids s = monthly(t.teacherId(), 700_000, "15.09.2026");
        studentBonus(s, 700_000, "16.09.2026");
        assertThat(periods(s.sg())).singleElement()
            .satisfies(p -> assertThat(p.getPaidOn()).isEqualTo(d("16.09.2026")));

        assertThat(calc(t.userId(), SEP).getPaidStudentCount()).isZero();
        properties.setCountDiscountCovered(true);
        assertThat(calc(t.userId(), SEP).getPaidStudentCount()).isEqualTo(1);
    }

    @Test
    void periodWithPaymentPlusDiscount_counts_andNewStudentNeedsRealPayment() {
        Staff t = teacherStaff();
        teacherRule(t, 0, 100_000);
        Ids mixed = monthly(t.teacherId(), 700_000, "15.09.2026");
        clock.setDate(d("16.09.2026"));
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        PaymentRequest r = new PaymentRequest();
        r.setStudentId(mixed.student());
        r.setGroupId(mixed.group());
        r.setAmount(BigDecimal.valueOf(700_000));                // brutto: naqd 100 000 + chegirma 600 000
        r.setDiscountAmount(BigDecimal.valueOf(600_000));
        r.setDiscountReason("Aksiya");
        r.setCashRegisterId(fixtures.cashRegister(false));
        r.setPaymentMethod(PaymentMethod.CASH);
        payments.createPayment(r, null);
        assertThat(calc(t.userId(), SEP).getPaidStudentCount()).isEqualTo(1);

        Long sales = staffUser(UserRole.SALES_MANAGER);
        rule(sales, UserRole.SALES_MANAGER, 0, 0, 150_000, null, 0);
        Ids bonusOnly = monthly(null, 700_000, "15.09.2026");
        jdbc.update("UPDATE students SET attributed_user_id = ? WHERE id IN (?, ?)",
            sales, mixed.student(), bonusOnly.student());
        studentBonus(bonusOnly, 700_000, "17.09.2026");
        assertThat(calc(sales, SEP).getCalculationDetails().items().newStudents())
            .extracting(PayrollCalculationDetails.NewStudent::studentId).containsExactly(mixed.student());
    }

    // ── #8: moliya hisoboti ─────────────────────────────────────────────

    @Test
    void financeReport_includesPaidPayrollByRole_cancelledExcluded_notDoubleCounted() {
        Staff t = paidTeacher();                                     // to'lov 700 000 (15.09)
        Staff cancelled = paidTeacher();                             // to'lov 700 000 (15.09)
        Long admin = staffUser(UserRole.ADMIN);
        rule(admin, UserRole.ADMIN, 4_000_000, 0, 0, null, 0);
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        payroll.generatePayroll(SEP, YEAR, false);
        clock.setDate(d("30.09.2026"));
        Long reg = fixtures.cashRegister(false);
        PayrollPayDto viaCash = new PayrollPayDto();
        viaCash.setCashRegisterId(reg);
        for (Long userId : new Long[]{t.userId(), cancelled.userId(), admin}) {
            Long id = payrollOf(userId, SEP).getId();
            payroll.approve(id, null);
            payroll.markAsPaid(id, viaCash, null);
        }
        payroll.cancel(payrollOf(cancelled.userId(), SEP).getId(), "Xato");

        FinanceReportResponse report = finance.getFinanceReport(d("01.09.2026"), d("30.09.2026"));
        assertThat(report.getTotalIncome()).isEqualByComparingTo("1400000");
        assertThat(report.getTotalExpenses()).isEqualByComparingTo("0");       // kassa chiqimi Expense emas
        assertThat(report.getPayrollPaid()).isEqualByComparingTo("7100000");   // 3 100 000 + 4 000 000
        assertThat(report.getPayrollByRole())
            .containsOnlyKeys("TEACHER", "ADMIN");
        assertThat(report.getPayrollByRole().get("TEACHER")).isEqualByComparingTo("3100000");
        assertThat(report.getPayrollByRole().get("ADMIN")).isEqualByComparingTo("4000000");
        assertThat(report.getNetProfit()).isEqualByComparingTo("-5700000");
    }

    @Test
    void financeReport_usesPaidAtNotPayrollMonth() {
        Staff t = paidTeacher();
        Long id = draftFor(t.userId(), SEP).getId();
        payroll.approve(id, null);
        clock.setDate(d("02.10.2026"));
        payroll.markAsPaid(id, null, null);

        assertThat(finance.getFinanceReport(d("01.09.2026"), d("30.09.2026")).getPayrollPaid())
            .isEqualByComparingTo("0");
        FinanceReportResponse oct = finance.getFinanceReport(d("01.10.2026"), d("31.10.2026"));
        assertThat(oct.getPayrollPaid()).isEqualByComparingTo("3100000");
        assertThat(oct.getNetProfit()).isEqualByComparingTo(oct.getTotalIncome().subtract(oct.getTotalExpenses())
            .subtract(new BigDecimal("3100000")));
    }
}
