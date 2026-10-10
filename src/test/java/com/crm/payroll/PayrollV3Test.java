package com.crm.payroll;

import com.crm.dto.request.GroupRequest;
import com.crm.dto.response.PayrollCalculationDetails;
import com.crm.dto.response.PayrollCalculationDetails.Line;
import com.crm.dto.response.PayrollCalculationDetails.PaidPeriod;
import com.crm.dto.response.PayrollResponse;
import com.crm.dto.response.SalaryCalculationDto;
import com.crm.entity.BalanceTransaction;
import com.crm.entity.BillingPeriod;
import com.crm.entity.Course;
import com.crm.entity.Group;
import com.crm.entity.Payroll;
import com.crm.entity.SalaryRule;
import com.crm.entity.SalaryRuleKpiTier;
import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.entity.StudentStatusHistory;
import com.crm.entity.enums.BalanceTransactionType;
import com.crm.entity.enums.BillingPeriodStatus;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.LessonType;
import com.crm.entity.enums.PaymentType;
import com.crm.entity.enums.StudentStatus;
import com.crm.entity.enums.UserRole;
import com.crm.repository.BalanceTransactionRepository;
import com.crm.repository.CourseRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentStatusHistoryRepository;
import com.crm.service.GroupService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Oylik v3 (payroll-v2 §14, buyurtmachi qarorlari 2026-10-10) — oktyabr 2026 oyligidan: dars turi bo'yicha ulush,
 * chala to'lov ulushi, stavka, KPI pog'onalari ("faol &gt; chegara"), yangi "faol" ta'rifi.
 */
class PayrollV3Test extends PayrollItBase {

    private static final int NOV = 11;
    private static final AtomicInteger PHONE = new AtomicInteger();

    @Autowired CourseRepository courseRepo;
    @Autowired StudentGroupRepository sgRepo;
    @Autowired BalanceTransactionRepository txRepo;
    @Autowired StudentStatusHistoryRepository historyRepo;
    @Autowired GroupService groups;
    @Autowired com.crm.service.StudentService students;
    @Autowired ObjectMapper objectMapper;

    // ── yordamchilar ────────────────────────────────────────────────────

    private void teacherRuleV3(Staff s, long perGroup, Long perIndividual) {
        inTx(() -> ruleRepo.save(SalaryRule.builder()
            .role(UserRole.TEACHER)
            .user(userRepo.findById(s.userId()).orElseThrow())
            .fixedSalary(BigDecimal.ZERO)
            .perPayingStudent(BigDecimal.valueOf(perGroup))
            .perNewStudent(BigDecimal.ZERO)
            .perStudentIndividual(perIndividual != null ? BigDecimal.valueOf(perIndividual) : null)
            .effectiveFrom(LocalDate.of(2026, 1, 1))
            .isActive(true)
            .build()));
    }

    private void adminRuleV3(Long userId, long fixed, String rate, long perNew, Map<Integer, Long> tiers) {
        inTx(() -> {
            SalaryRule r = SalaryRule.builder()
                .role(UserRole.ADMIN)
                .user(userRepo.findById(userId).orElseThrow())
                .fixedSalary(BigDecimal.valueOf(fixed))
                .employmentRate(new BigDecimal(rate))
                .perPayingStudent(BigDecimal.ZERO)
                .perNewStudent(BigDecimal.valueOf(perNew))
                .effectiveFrom(LocalDate.of(2026, 1, 1))
                .isActive(true)
                .build();
            tiers.forEach((threshold, bonus) -> r.getKpiTiers().add(SalaryRuleKpiTier.builder()
                .salaryRule(r).threshold(threshold).bonus(BigDecimal.valueOf(bonus)).build()));
            ruleRepo.save(r);
        });
    }

    private Long group(Long teacherId, long fee, LessonType type) {
        Long g = fixtures.group(fixtures.course(fee), GroupStatus.ACTIVE, teacherId);
        if (type == LessonType.INDIVIDUAL) {
            inTx(() -> {
                Group gr = groupRepo.findById(g).orElseThrow();
                gr.setLessonType(LessonType.INDIVIDUAL);
                groupRepo.save(gr);
            });
        }
        return g;
    }

    private Ids enroll(Long group, String start) {
        Long student = fixtures.student();
        return new Ids(student, group, fixtures.enrollment(student, group).start(d(start)).save());
    }

    /** Oktyabr davri (01.10–31.10, 100 000) bilan faol o'quvchi — servislarsiz, to'g'ridan bazaga (tez). */
    private Long octoberStudent(Long groupId, StudentStatus status, String paidOn, Long attributedTo) {
        return inTx(() -> {
            Student s = studentRepo.save(Student.builder()
                .firstName("Faol" + PHONE.incrementAndGet()).lastName("O'quvchi")
                .phone("+99893" + String.format("%07d", PHONE.get()))
                .status(status).attributedUserId(attributedTo)
                .build());
            StudentGroup sg = sgRepo.save(StudentGroup.builder()
                .student(s).group(groupRepo.findById(groupId).orElseThrow())
                .joinDate(d("01.10.2026")).paymentStartDate(d("01.10.2026"))
                .paymentType(PaymentType.MONTHLY).isTrial(false).isActive(true).balance(BigDecimal.ZERO)
                .build());
            BillingPeriod p = periodRepo.save(BillingPeriod.builder()
                .studentGroupId(sg.getId()).periodStart(d("01.10.2026")).periodEnd(d("31.10.2026"))
                .fee(BigDecimal.valueOf(100_000)).discountPercentage(BigDecimal.ZERO).amount(BigDecimal.valueOf(100_000))
                .status(BillingPeriodStatus.CHARGED)
                .build());
            BalanceTransaction charge = txRepo.save(BalanceTransaction.builder()
                .student(s).studentGroup(sg).type(BalanceTransactionType.PERIOD_CHARGE)
                .amount(BigDecimal.valueOf(-100_000)).balanceAfter(BigDecimal.valueOf(-100_000))
                .effectiveDate(d("01.10.2026")).billingPeriodId(p.getId())
                .build());
            p.setChargeTxId(charge.getId());
            if (paidOn != null) {
                BalanceTransaction payment = txRepo.save(BalanceTransaction.builder()
                    .student(s).studentGroup(sg).type(BalanceTransactionType.PAYMENT)
                    .amount(BigDecimal.valueOf(100_000)).balanceAfter(BigDecimal.ZERO)
                    .effectiveDate(d(paidOn))
                    .build());
                LocalDate paid = d(paidOn);
                p.setPaidOn(paid);
                p.setPaidTxId(payment.getId());
            }
            periodRepo.save(p);
            return s.getId();
        });
    }

    private void statusChange(Long studentId, StudentStatus from, StudentStatus to, String on) {
        inTx(() -> {
            StudentStatusHistory h = new StudentStatusHistory();
            h.setStudent(studentRepo.findById(studentId).orElseThrow());
            h.setFromStatus(from.name());
            h.setToStatus(to.name());
            h.setReason("test");
            h.setChangedAt(d(on).atTime(12, 0));
            historyRepo.save(h);
        });
    }

    private SalaryCalculationDto calc(Long userId, int month) {
        return calculator.calculateForUser(userId, month, YEAR);
    }

    private static Line line(SalaryCalculationDto c, String code) {
        return c.getCalculationDetails().lines().stream().filter(l -> code.equals(l.code())).findFirst().orElseThrow();
    }

    // ── D) O'qituvchi misoli: 3 guruh (24 o'quvchi, biri chala) + 1 individual → 4 917 143 ──

    @Test
    void teacher_example_4917143() {
        Staff t = teacherStaff();
        teacherRuleV3(t, 180_000, 700_000L);
        clock.setDate(d("01.10.2026"));
        Ids partial = null;
        for (int g = 0; g < 3; g++) {
            Long group = group(t.teacherId(), 700_000, LessonType.GROUP);
            for (int i = 0; i < 8; i++) {
                Ids ids = enroll(group, "01.10.2026");
                accrue(ids, "01.10.2026");
                boolean isPartial = g == 1 && i == 0;
                pay(ids, isPartial ? 300_000 : 700_000, "01.10.2026");
                if (isPartial) {
                    partial = ids;
                }
            }
        }
        Ids individual = enroll(group(t.teacherId(), 2_000_000, LessonType.INDIVIDUAL), "01.10.2026");
        accrue(individual, "01.10.2026");
        pay(individual, 2_000_000, "01.10.2026");
        clock.setDate(d("31.10.2026"));

        SalaryCalculationDto c = calc(t.userId(), OCT);
        assertThat(c.getCalculable()).isTrue();
        assertThat(c.getCalculationDetails().version()).isEqualTo(PayrollCalculationDetails.VERSION);
        assertThat(c.getTotalAmount()).isEqualByComparingTo("4917143");
        assertThat(line(c, PayrollCalculationDetails.PER_PAYING_STUDENT).formula()).isEqualTo("Guruh 23.4286 × 180 000");
        assertThat(line(c, PayrollCalculationDetails.PER_PAYING_STUDENT).amount()).isEqualByComparingTo("4217143");
        assertThat(line(c, PayrollCalculationDetails.PER_INDIVIDUAL_STUDENT).formula()).isEqualTo("Individual 1 × 700 000");
        assertThat(line(c, PayrollCalculationDetails.PER_INDIVIDUAL_STUDENT).amount()).isEqualByComparingTo("700000");
        assertThat(c.getPaidStudentCount()).isEqualTo(25);
        assertThat(c.getPaidStudentUnits()).isEqualByComparingTo("24.4286");

        Long partialSg = partial.sg();
        PaidPeriod pp = c.getCalculationDetails().items().paidPeriods().stream()
            .filter(p -> p.studentGroupId().equals(partialSg)).findFirst().orElseThrow();
        assertThat(pp.unit()).isEqualByComparingTo("0.4286");
        assertThat(pp.partial()).isTrue();
        assertThat(pp.paidInMonth()).isEqualByComparingTo("300000");
        assertThat(pp.periodAmount()).isEqualByComparingTo("700000");
        assertThat(pp.lessonType()).isEqualTo("GROUP");
        assertThat(c.getCalculationDetails().items().paidPeriods().stream()
                .filter(p -> "INDIVIDUAL".equals(p.lessonType())).toList())
            .singleElement()
            .satisfies(p -> assertThat(p.unit()).isEqualByComparingTo("1"));
    }

    // ── Chala to'lov: qolgan qism to'langan oyda; oldindan to'lov — davr oyida ──

    @Test
    void partialPayment_restCountsInMonthPaid_prepaymentInPeriodMonth_sumAtMostOne() {
        Staff t = teacherStaff();
        teacherRuleV3(t, 180_000, null);
        Long g = group(t.teacherId(), 700_000, LessonType.GROUP);
        Ids late = enroll(g, "01.10.2026");
        Ids prepaid = enroll(g, "01.10.2026");
        accrue(late, "01.10.2026");
        accrue(prepaid, "01.10.2026");
        pay(late, 300_000, "05.10.2026");
        pay(prepaid, 1_400_000, "05.10.2026");                          // oktyabr + noyabr oldindan
        accrue(late, "01.11.2026");
        accrue(prepaid, "01.11.2026");
        pay(late, 400_000, "05.11.2026");                               // oktyabr davrining qolgani

        SalaryCalculationDto oct = calc(t.userId(), OCT);
        SalaryCalculationDto nov = calc(t.userId(), NOV);
        // Oktyabr: 3/7 + 1 = 1.4286 → 257 143; noyabr: 4/7 (kech) + 1 (oldindan) = 1.5714 → 282 857
        assertThat(oct.getPaidStudentUnits()).isEqualByComparingTo("1.4286");
        assertThat(oct.getTotalAmount()).isEqualByComparingTo("257143");
        assertThat(nov.getPaidStudentUnits()).isEqualByComparingTo("1.5714");
        assertThat(nov.getTotalAmount()).isEqualByComparingTo("282857");
        PaidPeriod lateInNov = nov.getCalculationDetails().items().paidPeriods().stream()
            .filter(p -> p.studentGroupId().equals(late.sg())).findFirst().orElseThrow();
        assertThat(lateInNov.periodStart()).isEqualTo(d("01.10.2026"));
        assertThat(lateInNov.paidInMonth()).isEqualByComparingTo("400000");
        assertThat(lateInNov.paidTotal()).isEqualByComparingTo("700000");
        assertThat(lateInNov.partial()).isFalse();
        assertThat(lateInNov.countedOn()).isEqualTo(d("05.11.2026"));
        // Kech to'lovning noyabr davri hali to'lanmagan — sanalmaydi; oktyabr qayta hisobi o'zgarmaydi
        assertThat(nov.getCalculationDetails().items().paidPeriods())
            .noneMatch(p -> p.studentGroupId().equals(late.sg()) && p.periodStart().equals(d("01.11.2026")));
        assertThat(calc(t.userId(), OCT).getPaidStudentUnits()).isEqualByComparingTo("1.4286");
    }

    @Test
    void groupTransfer_movedBalanceCountsForNewTeacher_notTwiceForOld() {
        Staff ta = teacherStaff();
        Staff tb = teacherStaff();
        teacherRuleV3(ta, 180_000, null);
        teacherRuleV3(tb, 180_000, null);
        Ids a = enroll(group(ta.teacherId(), 700_000, LessonType.GROUP), "01.10.2026");
        accrue(a, "01.10.2026");
        pay(a, 1_400_000, "01.10.2026");                                // 1 davr yopilgan, 700 000 qoldiq
        Long groupB = group(tb.teacherId(), 700_000, LessonType.GROUP);

        clock.setDate(d("10.10.2026"));
        fixtures.loginAs(UserRole.ADMIN);
        com.crm.dto.request.TransferGroupRequest r = new com.crm.dto.request.TransferGroupRequest();
        r.setFromGroupId(a.group());
        r.setToGroupId(groupB);
        students.transferGroup(a.student(), r);
        Long sgB = inTx(() -> sgRepo.findByStudentIdAndGroupIdAndIsActiveTrue(a.student(), groupB).orElseThrow().getId());
        accrue(new Ids(a.student(), groupB, sgB), "01.11.2026");          // B dagi davr TRANSFER_IN bilan yopiladi

        // A o'qituvchisi: 1 400 000 to'langan, lekin faqat bitta davri bor — 1 birlik (qoldiq TRANSFER_OUT ni yopgan)
        SalaryCalculationDto aOct = calc(ta.userId(), OCT);
        assertThat(aOct.getPaidStudentUnits()).isEqualByComparingTo("1");
        assertThat(aOct.getTotalAmount()).isEqualByComparingTo("180000");
        assertThat(calc(ta.userId(), NOV).getPaidStudentUnits()).isEqualByComparingTo("0");

        // B o'qituvchisi: o'tgan balans davrni yopadi — noyabrda 1 birlik (qism sanasi max(10.10, 01.11))
        assertThat(calc(tb.userId(), OCT).getPaidStudentUnits()).isEqualByComparingTo("0");
        SalaryCalculationDto bNov = calc(tb.userId(), NOV);
        assertThat(bNov.getPaidStudentUnits()).isEqualByComparingTo("1");
        assertThat(bNov.getTotalAmount()).isEqualByComparingTo("180000");
        assertThat(bNov.getCalculationDetails().items().paidPeriods()).singleElement().satisfies(p -> {
            assertThat(p.studentGroupId()).isEqualTo(sgB);
            assertThat(p.paidInMonth()).isEqualByComparingTo("700000");
            assertThat(p.countedOn()).isEqualTo(d("01.11.2026"));
            assertThat(p.partial()).isFalse();
        });
    }

    @Test
    void perLesson_proportionalToPayment_monthlyEquivalentIsPriceTimes12() {
        Staff t = teacherStaff();
        teacherRuleV3(t, 180_000, null);
        Ids ids = perLesson(t.teacherId(), 60_000, "01.10.2026");
        pay(ids, 480_000, "01.10.2026");                                // 8 dars oldindan
        for (String day : List.of("02.10.2026", "05.10.2026", "07.10.2026", "09.10.2026",
                "12.10.2026", "14.10.2026", "16.10.2026", "19.10.2026", "21.10.2026", "23.10.2026")) {
            clock.setDate(d(day));
            attend(ids, day, com.crm.entity.enums.AttendanceStatus.PRESENT);
        }
        SalaryCalculationDto c = calc(t.userId(), OCT);
        // 10 dars hisoblandi, 8 tasi to'langan: 480 000 / (60 000 × 12) = 0.6667 → 120 000
        PayrollCalculationDetails.LessonEnrollment le = c.getCalculationDetails().items().lessonEnrollments().get(0);
        assertThat(le.lessons()).isEqualTo(8);
        assertThat(le.paidInMonth()).isEqualByComparingTo("480000");
        assertThat(le.monthlyEquivalent()).isEqualByComparingTo("720000");
        assertThat(le.share()).isEqualByComparingTo("0.6667");
        assertThat(c.getTotalAmount()).isEqualByComparingTo("120000");
    }

    @Test
    void individualStudent_withoutIndividualShare_notCalculable_notSilentZero() {
        Staff t = teacherStaff();
        teacherRuleV3(t, 180_000, null);
        Ids grp = enroll(group(t.teacherId(), 700_000, LessonType.GROUP), "01.10.2026");
        accrue(grp, "01.10.2026");
        pay(grp, 700_000, "01.10.2026");
        assertThat(calc(t.userId(), OCT).getCalculable()).isTrue();     // individual yo'q — hisoblanadi

        Ids ind = enroll(group(t.teacherId(), 2_000_000, LessonType.INDIVIDUAL), "01.10.2026");
        accrue(ind, "01.10.2026");
        pay(ind, 2_000_000, "01.10.2026");
        SalaryCalculationDto c = calc(t.userId(), OCT);
        assertThat(c.getCalculable()).isFalse();
        assertThat(c.getMessageCode()).isEqualTo("INDIVIDUAL_SHARE_MISSING");

        fixtures.loginAs(UserRole.SUPER_ADMIN);
        assertThat(payroll.generatePayroll(OCT, YEAR, false).skipped())
            .anyMatch(s -> s.userId().equals(t.userId()) && "INDIVIDUAL_SHARE_MISSING".equals(s.code()));
    }

    // ── D) Admin misoli: 0.75 stavka, 26 yangi o'quvchi, 520 faol → 5 300 000 ──

    @Test
    void admin_example_5300000_andPayrollSavedWithoutTeacher() {
        Long admin = staffUser(UserRole.ADMIN);
        adminRuleV3(admin, 4_000_000, "0.75", 50_000, Map.of(500, 1_000_000L, 600, 1_500_000L));
        Long g = fixtures.group(fixtures.course(100_000));
        for (int i = 0; i < 520; i++) {
            octoberStudent(g, StudentStatus.ACTIVE, "01.10.2026", i < 26 ? admin : null);
        }
        clock.setDate(d("31.10.2026"));

        SalaryCalculationDto c = calc(admin, OCT);
        assertThat(c.getTotalAmount()).isEqualByComparingTo("5300000");
        assertThat(c.getBaseSalary()).isEqualByComparingTo("3000000");
        assertThat(line(c, PayrollCalculationDetails.FIXED).formula()).isEqualTo("Fiksa 4 000 000 × 0.75");
        assertThat(line(c, PayrollCalculationDetails.PER_NEW_STUDENT).formula()).isEqualTo("Yangi o'quvchi 26 × 50 000");
        assertThat(line(c, PayrollCalculationDetails.PER_NEW_STUDENT).amount()).isEqualByComparingTo("1300000");
        assertThat(line(c, PayrollCalculationDetails.KPI).formula()).isEqualTo("KPI: 520 > 500 → 1 000 000");
        assertThat(c.getTotalActiveStudents()).isEqualTo(520);
        assertThat(c.getCalculationDetails().items().kpi().tiers()).hasSize(2);

        // Oylik teacher_id siz saqlanadi (V82), tasdiqlanadi
        PayrollResponse draft = draftFor(admin, OCT);
        Payroll saved = payroll(draft.getId());
        assertThat(saved.getTeacher()).isNull();
        assertThat(saved.getCalcVersion()).isEqualTo(PayrollCalculationDetails.VERSION);
        assertThat(jdbc.queryForObject("SELECT teacher_id FROM payroll WHERE id = ?", Long.class, draft.getId())).isNull();
        assertThat(saved.getNetSalary()).isEqualByComparingTo("5300000");
        assertThat(payroll.approve(draft.getId(), new BigDecimal("5300000")).getStatus()).isEqualTo(com.crm.entity.enums.PayrollStatus.APPROVED);
    }

    @Test
    void kpi_strictlyGreaterThanThreshold_highestTierOnly() {
        Long admin = staffUser(UserRole.ADMIN);
        adminRuleV3(admin, 1_000_000, "1", 0, Map.of(3, 200_000L, 4, 300_000L));
        Long g = fixtures.group(fixtures.course(100_000));
        for (int i = 0; i < 3; i++) {
            octoberStudent(g, StudentStatus.ACTIVE, "01.10.2026", null);
        }
        clock.setDate(d("31.10.2026"));
        SalaryCalculationDto three = calc(admin, OCT);
        assertThat(three.getKpiApplied()).isFalse();                     // 3 > 3 — yo'q
        assertThat(line(three, PayrollCalculationDetails.KPI).formula()).isEqualTo("KPI: 3 ≤ 3 → 0");
        assertThat(line(three, PayrollCalculationDetails.FIXED).formula()).isEqualTo("Fiksa 1 000 000");

        octoberStudent(g, StudentStatus.ACTIVE, "01.10.2026", null);
        assertThat(calc(admin, OCT).getKpiAmount()).isEqualByComparingTo("200000");     // 4 > 3
        octoberStudent(g, StudentStatus.ACTIVE, "01.10.2026", null);
        SalaryCalculationDto five = calc(admin, OCT);
        assertThat(five.getKpiAmount()).isEqualByComparingTo("300000");                 // 5 > 4 — yig'ilmaydi
        assertThat(line(five, PayrollCalculationDetails.KPI).formula()).isEqualTo("KPI: 5 > 4 → 300 000");
    }

    @Test
    void activeDefinition_statusAtMonthEnd_paidOrPrepaid_perLessonPaymentInMonth() {
        Long admin = staffUser(UserRole.ADMIN);
        adminRuleV3(admin, 1_000_000, "1", 0, Map.of(0, 1L));
        Long g = fixtures.group(fixtures.course(100_000));
        octoberStudent(g, StudentStatus.ACTIVE, "10.10.2026", null);                    // to'lagan — ha
        octoberStudent(g, StudentStatus.ACTIVE, null, null);                            // to'lamagan — yo'q
        octoberStudent(g, StudentStatus.ACTIVE, "20.09.2026", null);                    // oldindan — ha
        Long leftInOct = octoberStudent(g, StudentStatus.LEFT, "01.10.2026", null);     // 20.10 da ketgan — yo'q
        statusChange(leftInOct, StudentStatus.ACTIVE, StudentStatus.LEFT, "20.10.2026");
        Long leftInNov = octoberStudent(g, StudentStatus.LEFT, "01.10.2026", null);     // 05.11 da ketgan — ha
        statusChange(leftInNov, StudentStatus.ACTIVE, StudentStatus.LEFT, "05.11.2026");
        Ids perLessonPaid = perLesson(null, 60_000, "01.10.2026");                      // darsbay, oktyabrda to'lagan — ha
        pay(perLessonPaid, 120_000, "15.10.2026");
        Ids perLessonOld = perLesson(null, 60_000, "01.09.2026");                       // sentyabrda to'lagan — yo'q
        pay(perLessonOld, 120_000, "15.09.2026");
        clock.setDate(d("10.11.2026"));

        assertThat(calc(admin, OCT).getTotalActiveStudents()).isEqualTo(4);
    }

    @Test
    void v2DraftInOctober_approveRequiresRecalculation() {
        Long admin = staffUser(UserRole.ADMIN);
        adminRuleV3(admin, 1_000_000, "1", 0, Map.of());
        clock.setDate(d("31.10.2026"));
        Long id = draftFor(admin, OCT).getId();
        inTx(() -> {
            Payroll p = payrollRepo.findById(id).orElseThrow();
            p.setCalcVersion(PayrollCalculationDetails.VERSION_V2);
            payrollRepo.save(p);
        });
        assertCode(() -> payroll.approve(id, null), "payroll.recalculateRequired");
        payroll.recalculate(id);
        assertThat(payroll.approve(id, null).getStatus()).isEqualTo(com.crm.entity.enums.PayrollStatus.APPROVED);
    }

    // ── Dars turi: guruh / kurs, accrual snapshot ──

    @Test
    void lessonType_groupDefaultsFromCourse_invalidRejected_accrualSnapshots() {
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        Long courseId = fixtures.course(2_000_000);
        inTx(() -> {
            Course c = courseRepo.findById(courseId).orElseThrow();
            c.setLessonType(LessonType.INDIVIDUAL);
            courseRepo.save(c);
        });
        GroupRequest r = new GroupRequest();
        r.setGroupName("IND/Turk tili");
        r.setCourseId(courseId);
        r.setStartDate(d("01.09.2026"));
        Long groupId = groups.createGroup(r).getId();
        assertThat(groups.getGroupById(groupId).getLessonType()).isEqualTo(LessonType.INDIVIDUAL);

        r.setLessonType("guruh");
        assertCode(() -> groups.updateGroup(groupId, r), "group.lessonType.invalid");
        r.setLessonType(null);                                           // berilmasa — o'zgarmaydi
        assertThat(groups.updateGroup(groupId, r).getLessonType()).isEqualTo(LessonType.INDIVIDUAL);

        Ids ids = enroll(groupId, "01.10.2026");
        accrue(ids, "01.10.2026");
        assertThat(periods(ids.sg()).get(0).getLessonType()).isEqualTo(LessonType.INDIVIDUAL);

        r.setLessonType("group");
        assertThat(groups.updateGroup(groupId, r).getLessonType()).isEqualTo(LessonType.GROUP);
        assertThat(periods(ids.sg()).get(0).getLessonType()).isEqualTo(LessonType.INDIVIDUAL);   // snapshot qoladi
    }

    // ── V82 NOT NULL DEFAULT ustunlari: null hech qayerdan yetib bormaydi ──

    @Test
    void v82NotNullColumns_neverNull_entityAndRawInsert() {
        Long userId = staffUser(UserRole.ADMIN);
        Long ruleId = inTx(() -> {
            SalaryRule r = SalaryRule.builder().role(UserRole.ADMIN).user(userRepo.findById(userId).orElseThrow())
                .fixedSalary(BigDecimal.ONE).employmentRate(null).build();
            return ruleRepo.save(r).getId();
        });
        assertThat(jdbc.queryForObject("SELECT employment_rate FROM salary_rules WHERE id = ?", BigDecimal.class, ruleId))
            .isEqualByComparingTo("1.00");
        inTx(() -> {
            SalaryRule r = ruleRepo.findById(ruleId).orElseThrow();
            r.setEmploymentRate(null);
            r.setFixedSalary(BigDecimal.TEN);                            // @PreUpdate ham ishlasin
        });
        assertThat(jdbc.queryForObject("SELECT employment_rate FROM salary_rules WHERE id = ?", BigDecimal.class, ruleId))
            .isEqualByComparingTo("1.00");

        // Xom INSERT employment_rate siz — Hibernate yaratgan sxemada ham DEFAULT 1.00 (@ColumnDefault, V82 bilan bir xil)
        jdbc.update("""
            INSERT INTO salary_rules (role, user_id, base_salary, per_student_fee, new_student_bonus, kpi_bonus,
                                      is_active, created_at, updated_at)
            VALUES ('ADMIN', ?, 1, 0, 0, 0, TRUE, now(), now())
            """, userId);
        assertThat(jdbc.queryForObject(
            "SELECT employment_rate FROM salary_rules WHERE user_id = ? AND id <> ?", BigDecimal.class, userId, ruleId))
            .isEqualByComparingTo("1.00");

        Long courseId = fixtures.course(700_000);
        Long groupId = inTx(() -> groupRepo.save(Group.builder().groupName("Null tur")
            .course(courseRepo.findById(courseId).orElseThrow()).startDate(d("01.09.2026")).lessonType(null)
            .build()).getId());
        assertThat(jdbc.queryForObject("SELECT lesson_type FROM groups WHERE id = ?", String.class, groupId))
            .isEqualTo("GROUP");
    }

    // ── Qoida API: pog'onalar, stavka, eski kpiThreshold ──

    @Test
    void salaryRuleApi_kpiTiers_rate_legacyThreshold() throws Exception {
        Long admin = staffUser(UserRole.ADMIN);
        mvc.perform(post("/api/salary-rules").with(user("sa").roles("SUPER_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"role":"ADMIN","userId":%d,"fixedSalary":4000000,"employmentRate":0.75,"perNewStudent":50000,
                     "kpiTiers":[{"threshold":600,"bonus":1500000},{"threshold":500,"bonus":1000000}],
                     "effectiveFrom":"2026-10-01"}
                    """.formatted(admin)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.employmentRate").value(0.75))
            .andExpect(jsonPath("$.data.kpiTiers[0].threshold").value(500))
            .andExpect(jsonPath("$.data.kpiTiers[1].bonus").value(1500000))
            .andExpect(jsonPath("$.data.kpiThreshold").value(501));    // eski ("≥") ko'rinish

        mvc.perform(post("/api/salary-rules").with(user("sa").roles("SUPER_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"ADMIN\",\"fixedSalary\":1,\"employmentRate\":1.5}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("salaryRule.employmentRate.invalid"));
        mvc.perform(post("/api/salary-rules").with(user("sa").roles("SUPER_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"ADMIN\",\"kpiTiers\":[{\"threshold\":5,\"bonus\":1},{\"threshold\":5,\"bonus\":2}]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("salaryRule.kpiTiers.invalid"));
        mvc.perform(post("/api/salary-rules").with(user("sa").roles("SUPER_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"SALES_MANAGER\",\"perStudentIndividual\":5}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("salaryRule.field.notApplicable"));

        Long admin2 = staffUser(UserRole.ADMIN);                         // eski maydonlar: "≥ 500" → "> 499"
        mvc.perform(post("/api/salary-rules").with(user("sa").roles("SUPER_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"ADMIN\",\"userId\":%d,\"fixedSalary\":1,\"kpiThreshold\":500,\"kpiBonus\":7}"
                    .formatted(admin2)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.kpiTiers[0].threshold").value(499))
            .andExpect(jsonPath("$.data.kpiThreshold").value(500))
            .andExpect(jsonPath("$.data.employmentRate").value(1));
    }
}
