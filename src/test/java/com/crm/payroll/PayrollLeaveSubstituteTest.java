package com.crm.payroll;

import com.crm.dto.response.PayrollCalculationDetails;
import com.crm.dto.response.PayrollCalculationDetails.Line;
import com.crm.dto.response.PayrollResponse;
import com.crm.dto.response.SalaryCalculationDto;
import com.crm.entity.Holiday;
import com.crm.entity.Leave;
import com.crm.entity.LessonSubstitution;
import com.crm.entity.SalaryRule;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.LeaveStatus;
import com.crm.entity.enums.LeaveType;
import com.crm.entity.enums.SubstitutionStatus;
import com.crm.entity.enums.UserRole;
import com.crm.repository.HolidayRepository;
import com.crm.repository.LeaveRepository;
import com.crm.repository.LessonSubstitutionRepository;
import com.crm.service.LeaveService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 6-bosqich: payroll-v2 ga {@code LEAVE_DEDUCTION} va {@code SUBSTITUTE_LESSONS} (leaves-exams-contracts §3).
 * Sentyabr 2026: 30 kun, yakshanbalar 6/13/20/27 → 26 ish kuni (Du–Sha); oktyabr 2026 — 27.
 */
class PayrollLeaveSubstituteTest extends PayrollItBase {

    @Autowired LeaveRepository leaveRepo;
    @Autowired LessonSubstitutionRepository substitutionRepo;
    @Autowired HolidayRepository holidayRepo;
    @Autowired LeaveService leaveService;

    private Long leave(Long userId, String from, String to, boolean paid) {
        return inTx(() -> leaveRepo.save(Leave.builder()
            .user(userRepo.findById(userId).orElseThrow())
            .leaveType(paid ? LeaveType.ANNUAL : LeaveType.FAMILY)
            .fromDate(d(from)).toDate(d(to))
            .status(LeaveStatus.APPROVED)
            .paid(paid)
            .build()).getId());
    }

    private void substitution(Long groupId, Long original, Long substitute, String date, SubstitutionStatus status) {
        inTx(() -> substitutionRepo.save(LessonSubstitution.builder()
            .group(groupRepo.findById(groupId).orElseThrow())
            .lessonDate(d(date))
            .originalTeacher(teacherRepo.findById(original).orElseThrow())
            .substituteTeacher(teacherRepo.findById(substitute).orElseThrow())
            .status(status)
            .conductedAt(status == SubstitutionStatus.CONDUCTED ? d(date).atTime(18, 40) : null)
            .createdAt(LocalDateTime.of(2026, 9, 1, 9, 0))
            .build()));
    }

    private void substituteRate(Staff s, long fixed, Long rate) {
        inTx(() -> ruleRepo.save(SalaryRule.builder()
            .role(UserRole.TEACHER)
            .user(userRepo.findById(s.userId()).orElseThrow())
            .fixedSalary(BigDecimal.valueOf(fixed))
            .perPayingStudent(BigDecimal.ZERO)
            .perNewStudent(BigDecimal.ZERO)
            .kpiBonus(BigDecimal.ZERO)
            .substituteLessonRate(rate != null ? BigDecimal.valueOf(rate) : null)
            .effectiveFrom(LocalDate.of(2026, 1, 1))
            .isActive(true)
            .build()));
    }

    private static Optional<Line> line(PayrollCalculationDetails d, String code) {
        return d.lines().stream().filter(l -> l.code().equals(code)).findFirst();
    }

    private static void assertSumEqualsNet(PayrollCalculationDetails d) {
        BigDecimal sum = d.lines().stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(d.net());
    }

    // ── LEAVE_DEDUCTION ──────────────────────────────────────────────────

    @Test
    void unpaidLeave_threeWorkdays_deductsFixedShare_payrollColumns() {
        Staff t = teacherStaff();
        teacherRule(t, 3_000_000, 0);
        leave(t.userId(), "14.09.2026", "16.09.2026", false);

        PayrollResponse p = draftFor(t.userId(), SEP);
        PayrollCalculationDetails d = calculator.fromJson(payroll(p.getId()).getCalculationDetails());
        Line ded = line(d, PayrollCalculationDetails.LEAVE_DEDUCTION).orElseThrow();
        assertThat(ded.base()).isEqualByComparingTo("115385");      // 3 000 000 / 26, faqat ko'rsatish
        assertThat(ded.count()).isEqualByComparingTo("3");
        // Bitta yaxlitlash: uzs(3 000 000 × 3 / 26) = 346 154 (base × count = 346 155 emas)
        assertThat(ded.amount()).isEqualByComparingTo("-346154");
        assertThat(d.net()).isEqualByComparingTo("2653846");
        assertSumEqualsNet(d);
        assertThat(d.items().leaves()).singleElement().satisfies(l -> {
            assertThat(l.paid()).isFalse();
            assertThat(l.workdaysInMonth()).isEqualTo(3);
        });
        assertThat(d.version()).isEqualTo(PayrollCalculationDetails.VERSION);
        assertThat(p.getDeductions()).isEqualByComparingTo("346154");
        assertThat(p.getBasicSalary()).isEqualByComparingTo("3000000");
        assertThat(p.getAllowances()).isEqualByComparingTo("0");
        assertThat(p.getNetSalary()).isEqualByComparingTo("2653846");
    }

    @Test
    void sundayAndHoliday_areNotWorkdays_singleRounding() {
        Staff t = teacherStaff();
        teacherRule(t, 2_500_000, 0);
        inTx(() -> holidayRepo.save(Holiday.builder().holidayDate(d("21.09.2026")).name("Bayram")
            .createdAt(LocalDateTime.now()).build()));
        // 19 (shanba) — ish kuni; 20 (yakshanba) va 21 (bayram) — yo'q. Oyda 26 − 1 = 25 ish kuni
        leave(t.userId(), "19.09.2026", "21.09.2026", false);

        PayrollCalculationDetails d = calculator.calculateForUser(t.userId(), SEP, YEAR).getCalculationDetails();
        Line ded = line(d, PayrollCalculationDetails.LEAVE_DEDUCTION).orElseThrow();
        assertThat(ded.count()).isEqualByComparingTo("1");
        assertThat(ded.amount()).isEqualByComparingTo("-100000");   // 2 500 000 × 1 / 25
    }

    @Test
    void crossMonthLeave_splitByMonth_paidLeaveOnlyInItems_wholeMonthCappedAtFixed() {
        Staff t = teacherStaff();
        teacherRule(t, 2_700_000, 100_000);
        leave(t.userId(), "28.09.2026", "05.10.2026", false);

        SalaryCalculationDto sep = calculator.calculateForUser(t.userId(), SEP, YEAR);
        assertThat(line(sep.getCalculationDetails(), PayrollCalculationDetails.LEAVE_DEDUCTION).orElseThrow().amount())
            .isEqualByComparingTo("-311538");                        // uzs(2 700 000 × 3 / 26)
        SalaryCalculationDto oct = calculator.calculateForUser(t.userId(), OCT, YEAR);
        assertThat(line(oct.getCalculationDetails(), PayrollCalculationDetails.LEAVE_DEDUCTION).orElseThrow().count())
            .isEqualByComparingTo("4");                              // 1, 2, 3, 5 oktyabr
        assertThat(oct.getLeaveDeduction()).isEqualByComparingTo("400000");   // 2 700 000 × 4 / 27

        // Haqli ta'til — qator yo'q, items da bor
        Staff paid = teacherStaff();
        teacherRule(paid, 2_000_000, 0);
        leave(paid.userId(), "07.09.2026", "11.09.2026", true);
        PayrollCalculationDetails pd = calculator.calculateForUser(paid.userId(), SEP, YEAR).getCalculationDetails();
        assertThat(line(pd, PayrollCalculationDetails.LEAVE_DEDUCTION)).isEmpty();
        assertThat(pd.items().leaves()).singleElement().satisfies(l -> assertThat(l.paid()).isTrue());
        assertThat(pd.net()).isEqualByComparingTo("2000000");

        // Butun oy haqsiz: ayirma = FIXED, o'zgaruvchan qism (to'lagan o'quvchi) tegilmaydi
        Staff whole = teacherStaff();
        teacherRule(whole, 2_000_000, 100_000);
        pay(monthly(whole.teacherId(), 700_000, "15.09.2026"), 700_000, "15.09.2026");
        leave(whole.userId(), "01.09.2026", "30.09.2026", false);
        PayrollCalculationDetails wd = calculator.calculateForUser(whole.userId(), SEP, YEAR).getCalculationDetails();
        assertThat(line(wd, PayrollCalculationDetails.LEAVE_DEDUCTION).orElseThrow().amount())
            .isEqualByComparingTo("-2000000");
        assertThat(wd.net()).isEqualByComparingTo("100000");
        assertSumEqualsNet(wd);
    }

    @Test
    void staffRoles_deducted_accountantNotCalculated() {
        Long sales = staffUser(UserRole.SALES_MANAGER);
        rule(sales, UserRole.SALES_MANAGER, 2_600_000, 0, 0, null, 0);
        leave(sales, "14.09.2026", "15.09.2026", false);
        PayrollCalculationDetails d = calculator.calculateForUser(sales, SEP, YEAR).getCalculationDetails();
        assertThat(line(d, PayrollCalculationDetails.LEAVE_DEDUCTION).orElseThrow().amount())
            .isEqualByComparingTo("-200000");
        assertSumEqualsNet(d);

        Long accountant = staffUser(UserRole.ACCOUNTANT);
        leave(accountant, "14.09.2026", "15.09.2026", false);
        SalaryCalculationDto acc = calculator.calculateForUser(accountant, SEP, YEAR);
        assertThat(acc.getCalculable()).isFalse();
        assertThat(calculator.calculateAll(SEP, YEAR)).noneMatch(c -> c.getUserId().equals(accountant));
    }

    @Test
    void cancellingLeave_afterPayrollApproved_is409() {
        Staff t = teacherStaff();
        teacherRule(t, 2_600_000, 0);
        Long leaveId = leave(t.userId(), "14.09.2026", "16.09.2026", false);
        Long payrollId = draftFor(t.userId(), SEP).getId();
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        payroll.approve(payrollId, null);

        assertCode(() -> leaveService.cancel(leaveId, "Ishga chiqdi"), "leave.payrollLocked");
        assertThat(payroll(payrollId).getNetSalary()).isEqualByComparingTo("2300000");  // 2 600 000 − 3 × 100 000
    }

    // ── SUBSTITUTE_LESSONS ───────────────────────────────────────────────

    @Test
    void substituteLessons_onlyConductedCounted_mainTeacherUnaffected() {
        Staff main = teacherStaff();
        teacherRule(main, 3_000_000, 100_000);
        Ids ids = monthly(main.teacherId(), 700_000, "15.09.2026");
        pay(ids, 700_000, "15.09.2026");
        BigDecimal mainNetBefore = calculator.calculateForUser(main.userId(), SEP, YEAR).getTotalAmount();

        Staff sub = teacherStaff();
        substituteRate(sub, 1_000_000, 60_000L);
        for (String date : new String[]{"16.09.2026", "17.09.2026", "18.09.2026", "21.09.2026"}) {
            substitution(ids.group(), main.teacherId(), sub.teacherId(), date, SubstitutionStatus.CONDUCTED);
        }
        substitution(ids.group(), main.teacherId(), sub.teacherId(), "22.09.2026", SubstitutionStatus.PLANNED);
        substitution(ids.group(), main.teacherId(), sub.teacherId(), "23.09.2026", SubstitutionStatus.CANCELLED);
        substitution(ids.group(), main.teacherId(), sub.teacherId(), "01.10.2026", SubstitutionStatus.CONDUCTED);

        Long id = draftFor(sub.userId(), SEP).getId();
        PayrollCalculationDetails d = calculator.fromJson(payroll(id).getCalculationDetails());
        Line s = line(d, PayrollCalculationDetails.SUBSTITUTE_LESSONS).orElseThrow();
        assertThat(s.base()).isEqualByComparingTo("60000");
        assertThat(s.count()).isEqualByComparingTo("4");
        assertThat(s.amount()).isEqualByComparingTo("240000");
        assertThat(d.net()).isEqualByComparingTo("1240000");
        assertThat(d.rule().substituteLessonRate()).isEqualByComparingTo("60000");
        assertThat(d.items().substitutions()).hasSize(4)
            .allSatisfy(i -> assertThat(i.originalTeacherId()).isEqualTo(main.teacherId()));
        assertSumEqualsNet(d);
        assertThat(payroll(id).getAllowances()).isEqualByComparingTo("240000");

        // D6 / Q-B: asosiy o'qituvchidan ayirilmaydi, "to'lagan o'quvchi" qismi o'zgarmaydi
        SalaryCalculationDto mainAfter = calculator.calculateForUser(main.userId(), SEP, YEAR);
        assertThat(mainAfter.getTotalAmount()).isEqualByComparingTo(mainNetBefore);
        assertThat(line(mainAfter.getCalculationDetails(), PayrollCalculationDetails.SUBSTITUTE_LESSONS)).isEmpty();
        assertThat(mainAfter.getPaidStudentCount()).isEqualTo(1);
    }

    @Test
    void substituteRate_missing_notCalculable_roleRuleFallback() {
        Staff main = teacherStaff();
        Long group = fixtures.group(fixtures.course(500_000), GroupStatus.ACTIVE, main.teacherId());
        Staff sub = teacherStaff();
        substituteRate(sub, 1_000_000, null);
        substitution(group, main.teacherId(), sub.teacherId(), "16.09.2026", SubstitutionStatus.CONDUCTED);

        SalaryCalculationDto missing = calculator.calculateForUser(sub.userId(), SEP, YEAR);
        assertThat(missing.getCalculable()).isFalse();
        assertThat(missing.getMessageCode()).isEqualTo("SUBSTITUTE_RATE_MISSING");

        // Rol qoidasidagi stavka — shaxsiy qoidada yo'q bo'lsa
        inTx(() -> ruleRepo.save(SalaryRule.builder().role(UserRole.TEACHER)
            .fixedSalary(BigDecimal.ZERO).perPayingStudent(BigDecimal.ZERO).perNewStudent(BigDecimal.ZERO)
            .kpiBonus(BigDecimal.ZERO).substituteLessonRate(BigDecimal.valueOf(50_000))
            .effectiveFrom(LocalDate.of(2026, 1, 1)).isActive(true).build()));
        SalaryCalculationDto withRole = calculator.calculateForUser(sub.userId(), SEP, YEAR);
        assertThat(withRole.getCalculable()).isTrue();
        assertThat(withRole.getSubstituteAmount()).isEqualByComparingTo("50000");
        assertThat(withRole.getTotalAmount()).isEqualByComparingTo("1050000");

        // O'rinbosar darsi yo'q oy — stavka shart emas
        Staff plain = teacherStaff();
        substituteRate(plain, 1_000_000, null);
        assertThat(calculator.calculateForUser(plain.userId(), SEP, YEAR).getCalculable()).isTrue();
    }

    @Test
    void salaryRule_substituteLessonRate_teacherOnly() throws Exception {
        Long teacherUser = teacherStaff().userId();
        Long admin = staffUser(UserRole.ADMIN);
        mvc.perform(post("/api/salary-rules").with(user("test-super_admin").roles("SUPER_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"TEACHER\",\"userId\":" + teacherUser + ",\"fixedSalary\":1000000,\"substituteLessonRate\":60000}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.substituteLessonRate").value(60000));
        mvc.perform(post("/api/salary-rules").with(user("test-super_admin").roles("SUPER_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"ADMIN\",\"userId\":" + admin + ",\"fixedSalary\":1000000,\"substituteLessonRate\":60000}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("salaryRule.field.notApplicable"));
    }
}
