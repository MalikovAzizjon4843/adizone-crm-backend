package com.crm.payroll;

import com.crm.dto.response.PayrollCalculationDetails;
import com.crm.dto.response.SalaryCalculationDto;
import com.crm.entity.BalanceTransaction;
import com.crm.entity.BillingPeriod;
import com.crm.entity.enums.AttendanceStatus;
import com.crm.entity.enums.BalanceTransactionType;
import com.crm.entity.enums.BonusPenaltyKind;
import com.crm.entity.enums.TeacherAttribution;
import com.crm.entity.enums.UserRole;
import com.crm.repository.BalanceTransactionRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Payroll v2 — qaror 5 (o'qituvchi atributsiyasi), 6 ("to'lagan" / "yangi" o'quvchi),
 * 7 (o'tgan oyni qayta hisoblash — bir xil natija).
 */
class PayrollAttributionTest extends PayrollItBase {

    @Autowired BalanceTransactionRepository txRepo;

    private SalaryCalculationDto calc(Long userId, int month) {
        return calculator.calculateForUser(userId, month, YEAR);
    }

    // ── Qaror 5: billing_periods.teacher_id — accrual paytidagi o'qituvchi ──

    @Test
    void accrual_recordsTeacherAtThatTime_teacherChangeKeepsPastPeriods() {
        Staff a = teacherStaff();
        Staff b = teacherStaff();
        teacherRule(a, 0, 100_000);
        teacherRule(b, 0, 100_000);
        Ids s = monthly(a.teacherId(), 700_000, "15.09.2026");
        pay(s, 700_000, "15.09.2026");                       // 15.09 davri: A
        changeTeacher(s.group(), b.teacherId());
        pay(s, 700_000, "15.10.2026");                       // 15.10 davri accrual: B

        List<BillingPeriod> ps = periods(s.sg());
        assertThat(ps).extracting(BillingPeriod::getTeacherId).containsExactly(a.teacherId(), b.teacherId());
        assertThat(ps).extracting(BillingPeriod::getTeacherSource).containsOnly(TeacherAttribution.LIVE);

        assertThat(calc(a.userId(), SEP).getPaidStudentCount()).isEqualTo(1);
        assertThat(calc(b.userId(), SEP).getPaidStudentCount()).isZero();
        assertThat(calc(a.userId(), OCT).getPaidStudentCount()).isZero();
        assertThat(calc(b.userId(), OCT).getPaidStudentCount()).isEqualTo(1);
    }

    @Test
    void lessonCharge_recordsTeacherOfThatLesson() {
        Staff a = teacherStaff();
        Staff b = teacherStaff();
        teacherRule(a, 0, 50_000);
        teacherRule(b, 0, 50_000);
        Ids s = perLesson(a.teacherId(), 80_000, "01.09.2026");
        clock.setDate(d("20.09.2026"));
        attend(s, "10.09.2026", AttendanceStatus.PRESENT);
        changeTeacher(s.group(), b.teacherId());
        attend(s, "17.09.2026", AttendanceStatus.PRESENT);

        List<BalanceTransaction> charges = inTx(() -> txRepo.findByStudentGroup_IdOrderByIdAsc(s.sg()));
        assertThat(charges).extracting(BalanceTransaction::getTeacherId).containsExactly(a.teacherId(), b.teacherId());
        assertThat(calc(a.userId(), SEP).getPaidStudentCount()).isEqualTo(1);
        assertThat(calc(b.userId(), SEP).getPaidStudentCount()).isEqualTo(1);
    }

    /** V54 backfill (PostgreSQL sintaksisi) — faqat pgtest da; H2 da o'tkazib yuboriladi. */
    @Test
    void v54_backfillsEstimatedTeacher_mapsPendingToDraft_isIdempotent() {
        Assumptions.assumeTrue(isPostgres(), "V54 — PostgreSQL skripti (pgtest)");
        Staff a = teacherStaff();
        Ids s = monthly(a.teacherId(), 700_000, "15.09.2026");
        pay(s, 700_000, "15.09.2026");
        jdbc.update("UPDATE billing_periods SET teacher_id = NULL, teacher_source = NULL");
        jdbc.update("""
            INSERT INTO payroll (uuid, user_id, month, year, status, net_salary, created_at)
            VALUES (gen_random_uuid(), ?, 8, 2026, 'PENDING', 100, now())
            """, a.userId());

        runV54();
        runV54();

        assertThat(periods(s.sg())).singleElement().satisfies(p -> {
            assertThat(p.getTeacherId()).isEqualTo(a.teacherId());
            assertThat(p.getTeacherSource()).isEqualTo(TeacherAttribution.ESTIMATED);
        });
        assertThat(jdbc.queryForObject("SELECT status FROM payroll WHERE month = 8", String.class)).isEqualTo("DRAFT");
    }

    // ── Qaror 6: "to'lagan" = shu oyda yopilgan davr; "yangi" = birinchi davri yopilgan ──

    @Test
    void paidStudents_onlyPeriodsClosedInMonth_cancelledAndPartialExcluded() {
        Staff t = teacherStaff();
        teacherRule(t, 0, 100_000);
        Ids paid = monthly(t.teacherId(), 700_000, "15.09.2026");
        Ids unpaid = monthly(t.teacherId(), 700_000, "15.09.2026");
        Ids partial = monthly(t.teacherId(), 700_000, "15.09.2026");
        Ids cancelled = monthly(t.teacherId(), 700_000, "15.09.2026");
        pay(paid, 700_000, "16.09.2026");
        accrue(unpaid, "16.09.2026");
        pay(partial, 300_000, "16.09.2026");
        Long p = pay(cancelled, 700_000, "16.09.2026");
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        payments.cancelPayment(p, "Xato kiritildi");

        SalaryCalculationDto c = calc(t.userId(), SEP);
        assertThat(c.getPaidStudentCount()).isEqualTo(1);
        assertThat(c.getCalculationDetails().items().paidPeriods())
            .singleElement().satisfies(pp -> {
                assertThat(pp.studentId()).isEqualTo(paid.student());
                assertThat(pp.paidOn()).isEqualTo(d("16.09.2026"));
            });
    }

    @Test
    void prepaidFuturePeriod_countsInItsOwnMonth_pastMonthUnchanged() {
        Staff t = teacherStaff();
        teacherRule(t, 0, 100_000);
        Ids s = monthly(t.teacherId(), 700_000, "15.09.2026");
        pay(s, 2_100_000, "15.09.2026");                     // 3 oy oldindan
        assertThat(calc(t.userId(), SEP).getPaidStudentCount()).isEqualTo(1);

        accrue(s, "15.11.2026");                              // 15.10 va 15.11 davrlari — paid_on = 15.09
        assertThat(periods(s.sg())).extracting(BillingPeriod::getPaidOn).containsOnly(d("15.09.2026"));
        assertThat(calc(t.userId(), SEP).getPaidStudentCount()).isEqualTo(1);
        assertThat(calc(t.userId(), OCT).getPaidStudentCount()).isEqualTo(1);
        assertThat(calc(t.userId(), OCT).getCalculationDetails().items().paidPeriods())
            .singleElement().satisfies(pp -> assertThat(pp.countedOn()).isEqualTo(d("15.10.2026")));
    }

    @Test
    void perLesson_countsEnrollmentOncePerMonth_refundedLessonExcluded() {
        Staff t = teacherStaff();
        teacherRule(t, 0, 50_000);
        Ids three = perLesson(t.teacherId(), 80_000, "01.09.2026");
        Ids excused = perLesson(t.teacherId(), 80_000, "01.09.2026");
        clock.setDate(d("25.09.2026"));
        attend(three, "08.09.2026", AttendanceStatus.PRESENT);
        attend(three, "15.09.2026", AttendanceStatus.LATE);
        attend(three, "22.09.2026", AttendanceStatus.PRESENT);
        attend(excused, "08.09.2026", AttendanceStatus.PRESENT);
        attend(excused, "08.09.2026", AttendanceStatus.EXCUSED);   // LESSON_REFUND — net 0

        SalaryCalculationDto c = calc(t.userId(), SEP);
        assertThat(c.getPaidStudentCount()).isEqualTo(1);
        assertThat(c.getCalculationDetails().items().lessonEnrollments()).singleElement().satisfies(l -> {
            assertThat(l.studentGroupId()).isEqualTo(three.sg());
            assertThat(l.lessons()).isEqualTo(3);
        });
        assertThat(inTx(() -> txRepo.findByStudentGroup_IdOrderByIdAsc(excused.sg())))
            .extracting(BalanceTransaction::getType)
            .containsExactly(BalanceTransactionType.LESSON_CHARGE, BalanceTransactionType.LESSON_REFUND);
    }

    @Test
    void newStudent_byFirstClosedPeriodDate_notByMinPaymentId() {
        Long sales = staffUser(UserRole.SALES_MANAGER);
        rule(sales, UserRole.SALES_MANAGER, 2_000_000, 0, 150_000, null, 0);
        Ids backdated = monthly(null, 700_000, "15.09.2026");
        Ids october = monthly(null, 700_000, "15.09.2026");
        jdbc.update("UPDATE students SET attributed_user_id = ? WHERE id IN (?, ?)",
            sales, backdated.student(), october.student());

        // Birinchi kiritilgan to'lov (kichik id) 01.10 sanali; keyin 16.09 sanali to'lov orqaga kiritiladi
        pay(backdated, 700_000, "01.10.2026", "01.10.2026");
        pay(backdated, 700_000, "03.10.2026", "16.09.2026");
        pay(october, 700_000, "02.10.2026", "02.10.2026");

        SalaryCalculationDto sep = calc(sales, SEP);
        assertThat(sep.getNewStudentCount()).isEqualTo(1);
        assertThat(sep.getCalculationDetails().items().newStudents()).singleElement().satisfies(n -> {
            assertThat(n.studentId()).isEqualTo(backdated.student());
            assertThat(n.countedOn()).isEqualTo(d("16.09.2026"));
            assertThat(n.source()).isEqualTo("PERIOD");
        });
        assertThat(sep.getTotalAmount()).isEqualByComparingTo("2150000");
        assertThat(calc(sales, OCT).getCalculationDetails().items().newStudents())
            .extracting(PayrollCalculationDetails.NewStudent::studentId).containsExactly(october.student());
    }

    // ── Qaror 7: deterministik ──────────────────────────────────────────

    @Test
    void pastMonth_recalculatesIdentically_afterTeacherChangeStatusAndNewData() {
        Staff a = teacherStaff();
        Staff b = teacherStaff();
        teacherRule(a, 3_000_000, 100_000);
        teacherRule(b, 3_000_000, 100_000);
        Ids s1 = monthly(a.teacherId(), 700_000, "15.09.2026");
        Ids s2 = monthly(a.teacherId(), 500_000, "20.09.2026");
        pay(s1, 700_000, "15.09.2026");
        pay(s2, 500_000, "20.09.2026");
        teacherBonus(a.teacherId(), BonusPenaltyKind.BONUS, 100_000, "25.09.2026");
        String before = calculator.toJson(calc(a.userId(), SEP).getCalculationDetails());

        // Hozirgi holat o'zgaradi: o'qituvchi, o'quvchi holati, yangi oylar, keyingi oy bonusi
        changeTeacher(s1.group(), b.teacherId());
        jdbc.update("UPDATE students SET status = 'LEFT' WHERE id = ?", s2.student());
        pay(s1, 1_400_000, "20.11.2026");
        accrue(s2, "20.11.2026");
        teacherBonus(a.teacherId(), BonusPenaltyKind.BONUS, 500_000, "05.10.2026");

        assertThat(calculator.toJson(calc(a.userId(), SEP).getCalculationDetails())).isEqualTo(before);
        assertThat(calc(a.userId(), SEP).getTotalAmount()).isEqualByComparingTo("3300000");
    }

    @Test
    void adminKpi_usesMonthEndBillingPeriods_notCurrentStudentStatus() {
        Long admin = staffUser(UserRole.ADMIN);
        rule(admin, UserRole.ADMIN, 4_000_000, 0, 0, 2, 500_000);
        Ids s1 = monthly(null, 700_000, "15.09.2026");
        Ids s2 = monthly(null, 700_000, "20.09.2026");
        Ids octOnly = monthly(null, 700_000, "01.10.2026");
        accrue(s1, "20.09.2026");
        accrue(s2, "20.09.2026");
        accrue(octOnly, "01.10.2026");

        SalaryCalculationDto c = calc(admin, SEP);
        assertThat(c.getCalculationDetails().items().kpi().actual()).isEqualTo(2);
        assertThat(c.getKpiApplied()).isTrue();
        assertThat(c.getTotalAmount()).isEqualByComparingTo("4500000");

        jdbc.update("UPDATE students SET status = 'LEFT' WHERE id IN (?, ?)", s1.student(), s2.student());
        SalaryCalculationDto again = calc(admin, SEP);
        assertThat(again.getCalculationDetails().items().kpi().actual()).isEqualTo(2);
        assertThat(again.getTotalAmount()).isEqualByComparingTo("4500000");
    }

    private boolean isPostgres() {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) c ->
            c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres")));
    }

    private void runV54() {
        jdbc.execute((ConnectionCallback<Void>) c -> {
            ScriptUtils.executeSqlScript(c, new EncodedResource(new ClassPathResource("db/migration/V54__payroll_v2.sql")),
                false, false, ScriptUtils.DEFAULT_COMMENT_PREFIX, ScriptUtils.EOF_STATEMENT_SEPARATOR,
                ScriptUtils.DEFAULT_BLOCK_COMMENT_START_DELIMITER, ScriptUtils.DEFAULT_BLOCK_COMMENT_END_DELIMITER);
            return null;
        });
    }
}
