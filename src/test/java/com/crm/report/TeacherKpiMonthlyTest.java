package com.crm.report;

import com.crm.billing.AccrualService;
import com.crm.billing.support.AbstractBillingIT;
import com.crm.dto.request.PaymentRequest;
import com.crm.dto.response.TeacherKpiScoresDto;
import com.crm.entity.Attendance;
import com.crm.entity.TeacherKpiMonthly;
import com.crm.entity.enums.AttendanceStatus;
import com.crm.entity.enums.ExitReasonCode;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.PaymentMethod;
import com.crm.entity.enums.UserRole;
import com.crm.exception.BadRequestException;
import com.crm.repository.AttendanceRepository;
import com.crm.repository.GroupRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentRepository;
import com.crm.repository.TeacherKpiMonthlyRepository;
import com.crm.service.GroupService;
import com.crm.service.PaymentService;
import com.crm.service.TeacherKpiService;
import com.crm.service.TeacherKpiSnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O'qituvchi KPI: oy bo'yicha, davrlar asosidagi o'z vaqtida to'lov, maxraj 0 → null,
 * oy yakuni snapshot'i va qayta hisoblash.
 */
class TeacherKpiMonthlyTest extends AbstractBillingIT {

    @Autowired TeacherKpiService kpi;
    @Autowired TeacherKpiSnapshotService snapshots;
    @Autowired TeacherKpiMonthlyRepository monthlyRepo;
    @Autowired AccrualService accrual;
    @Autowired PaymentService payments;
    @Autowired GroupService groups;
    @Autowired AttendanceRepository attendanceRepo;
    @Autowired StudentRepository studentRepo;
    @Autowired GroupRepository groupRepo;
    @Autowired StudentGroupRepository sgRepo;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    private Long teacher;
    private Long group;

    private Long monthly(String start) {
        Long s = fixtures.student();
        return fixtures.enrollment(s, group).start(d(start)).discount("10").save();
    }

    private Long studentOf(Long sg) {
        return sgRepo.findStudentIdById(sg).orElseThrow();
    }

    private void accrueOn(Long sg, String date) {
        clock.setDate(d(date));
        accrual.accrueUpTo(sg, d(date));
    }

    private void pay(Long sg, long amount, String date) {
        clock.setDate(d(date));
        fixtures.loginAs(UserRole.ACCOUNTANT);
        PaymentRequest r = new PaymentRequest();
        r.setStudentId(studentOf(sg));
        r.setGroupId(group);
        r.setAmount(BigDecimal.valueOf(amount));
        r.setCashRegisterId(fixtures.cashRegister(false));
        r.setPaymentMethod(PaymentMethod.CASH);
        payments.createPayment(r, null);
    }

    private void attend(Long sg, String date, AttendanceStatus st) {
        Long student = studentOf(sg);
        inTx(() -> attendanceRepo.save(Attendance.builder()
            .student(studentRepo.findById(student).orElseThrow())
            .group(groupRepo.findById(group).orElseThrow())
            .attendanceDate(d(date))
            .status(st)
            .build()));
    }

    /**
     * Sentabr 2026, o'qituvchi T, bitta guruh (630 000 / oy, grace 0 — grace_until = muddat kuni (R1)):
     * <ul>
     *   <li>a: muddat 01.09, to'lov 01.09 (muddat kuni) — o'z vaqtida;</li>
     *   <li>b: muddat 05.09, to'lov 12.09 — kechikkan;</li>
     *   <li>c: muddat 10.09, to'lanmagan — 15.09 da natijasi ma'lum (to'lanmadi);</li>
     *   <li>e: muddat 15.09 (bugun), to'lanmagan — kun tugamaguncha natijasi noma'lum (kutilmoqda);</li>
     *   <li>x: PER_LESSON (davr yo'q), 10.09 da narx sababli ketdi — churn.</li>
     * </ul>
     * Davomat: 2 keldi (PRESENT, LATE), 1 kelmadi.
     */
    private Long[] september() {
        teacher = fixtures.teacher();
        group = fixtures.group(fixtures.course(700_000), GroupStatus.ACTIVE, teacher);
        Long a = monthly("01.09.2026");
        Long b = monthly("05.09.2026");
        Long c = monthly("10.09.2026");
        Long e = monthly("15.09.2026");
        accrueOn(a, "01.09.2026");
        pay(a, 630_000, "01.09.2026");
        accrueOn(b, "05.09.2026");
        pay(b, 630_000, "12.09.2026");
        accrueOn(c, "10.09.2026");
        accrueOn(e, "15.09.2026");

        Long xs = fixtures.student();
        Long x = fixtures.enrollment(xs, group).start(d("01.09.2026")).perLesson(50_000).save();
        clock.setDate(d("10.09.2026"));
        fixtures.loginAs(UserRole.ADMIN);
        groups.removeStudentFromGroup(group, xs, "LEFT", null, ExitReasonCode.PRICE);

        attend(a, "03.09.2026", AttendanceStatus.PRESENT);
        attend(b, "03.09.2026", AttendanceStatus.LATE);
        attend(c, "03.09.2026", AttendanceStatus.ABSENT);
        clock.setDate(d("15.09.2026"));
        return new Long[]{a, b, c, e, x};
    }

    @Test
    void currentMonth_live_periodBasedOnTime_notTodaysSnapshot() {
        september();
        TeacherKpiScoresDto s = kpi.scoresForMonth(teacher, kpi.resolveMonth("2026-09"));

        assertThat(s.getSource()).isEqualTo(TeacherKpiService.SOURCE_LIVE);
        assertThat(s.getMonth()).isEqualTo("2026-09");
        assertThat(s.getAttendancePresent()).isEqualTo(2);
        assertThat(s.getAttendanceTotal()).isEqualTo(3);
        assertThat(s.getAttendanceRate()).isEqualTo(66.7);
        // a, b, c natijasi ma'lum; e muddati bugun — hali kutilmoqda
        assertThat(s.getPeriodsDecided()).isEqualTo(3);
        assertThat(s.getPeriodsPaid()).isEqualTo(2);
        assertThat(s.getPeriodsOnTime()).isEqualTo(1);
        assertThat(s.getPeriodsPending()).isEqualTo(1);
        assertThat(s.getPaymentRate()).isEqualTo(66.7);
        // Eski formula (faol − bugun OVERDUE) / faol: c ham "bugun qarzdor" — 75%, b kechikkani ko'rinmasdi
        assertThat(s.getOnTimePaymentRate()).isEqualTo(33.3);
        // 4 ochiq / (4 + 1 ketgan)
        assertThat(s.getOpenAtEnd()).isEqualTo(4);
        assertThat(s.getChurned()).isEqualTo(1);
        assertThat(s.getRetentionRate()).isEqualTo(80.0);
        assertThat(s.getOverallScore()).isEqualTo(61.7);
        assertThat(s.getInsufficientData()).isFalse();
    }

    @Test
    void zeroDenominators_areNull_not100() {
        Long t = fixtures.teacher();
        Long g = fixtures.group(fixtures.course(700_000), GroupStatus.ACTIVE, t);
        Long s = fixtures.student();
        Long sg = fixtures.enrollment(s, g).start(d("15.09.2026")).discount("10").save();
        accrueOn(sg, "15.09.2026");       // muddat bugun — kun tugamaguncha natijasi noma'lum
        clock.setDate(d("15.09.2026"));

        TeacherKpiScoresDto live = kpi.computeScores(t, d("01.09.2026"), d("15.09.2026"));
        assertThat(live.getAttendanceRate()).isNull();            // davomat yo'q
        assertThat(live.getPaymentRate()).isNull();               // natijasi ma'lum davr yo'q
        assertThat(live.getOnTimePaymentRate()).isNull();         // ilgari: 1/1 = 100%
        assertThat(live.getPeriodsPending()).isEqualTo(1);
        assertThat(live.getRetentionRate()).isEqualTo(100.0);     // 1 ochiq, 0 ketgan — maxraj 1
        assertThat(live.getOverallScore()).isEqualTo(100.0);

        Long empty = fixtures.teacher();
        TeacherKpiScoresDto none = kpi.computeScores(empty, d("01.09.2026"), d("15.09.2026"));
        assertThat(none.getAttendanceRate()).isNull();
        assertThat(none.getPaymentRate()).isNull();
        assertThat(none.getOnTimePaymentRate()).isNull();
        assertThat(none.getRetentionRate()).isNull();
        assertThat(none.getOverallScore()).isNull();
        assertThat(none.getInsufficientData()).isTrue();
    }

    @Test
    void monthEndSnapshot_readForClosedMonth_recomputeBySuperAdmin() throws Exception {
        Long[] sgs = september();

        // 1-oktabr 01:00 — job o'tgan oyni yozadi; endi e ning muddati (15.09) ham o'tgan
        clock.setDate(d("01.10.2026"));
        snapshots.monthly();
        TeacherKpiMonthly row = inTx(() -> monthlyRepo.findByTeacherIdAndMonthStart(teacher, d("01.09.2026")))
            .orElseThrow();
        assertThat(row.getSource()).isEqualTo(TeacherKpiMonthly.SOURCE_JOB);
        assertThat(row.getPeriodsDecided()).isEqualTo(4);
        assertThat(row.getPeriodsPending()).isZero();
        assertThat(row.getPaymentRate()).isEqualTo(50.0);
        assertThat(row.getOnTimePaymentRate()).isEqualTo(25.0);
        assertThat(row.getRetentionRate()).isEqualTo(80.0);
        assertThat(row.getStudentCount()).isEqualTo(4);

        // Yopilgan oy snapshot'dan o'qiladi — keyingi to'lov uni o'zgartirmaydi
        pay(sgs[3], 630_000, "02.10.2026");
        clock.setDate(d("02.10.2026"));
        mvc.perform(get("/api/teachers/{id}/kpi", teacher).param("month", "2026-09")
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.month").value("2026-09"))
            .andExpect(jsonPath("$.current.source").value("SNAPSHOT"))
            .andExpect(jsonPath("$.current.paymentRate").value(50.0))
            .andExpect(jsonPath("$.current.onTimePaymentRate").value(25.0));

        mvc.perform(get("/api/teachers/kpi/ranking").param("month", "2026-09")
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.month").value("2026-09"))
            .andExpect(jsonPath("$.data.source").value("SNAPSHOT"))
            .andExpect(jsonPath("$.data.teachers[0].teacherId").value(teacher))
            .andExpect(jsonPath("$.data.teachers[0].onTimePaymentRate").value(25.0));

        // Qayta hisoblash — faqat SUPER_ADMIN, faqat yopilgan oy
        mvc.perform(post("/api/teachers/kpi/snapshots").param("month", "2026-09")
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/teachers/kpi/snapshots").param("month", "2026-10")
                .with(user("sa").roles("SUPER_ADMIN")))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/teachers/kpi/snapshots").param("month", "2026-09")
                .with(user("sa").roles("SUPER_ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.month").value("2026-09"));

        TeacherKpiScoresDto after = kpi.scoresForMonth(teacher, kpi.resolveMonth("2026-09"));
        assertThat(after.getSource()).isEqualTo(TeacherKpiService.SOURCE_SNAPSHOT);
        assertThat(after.getPeriodsPaid()).isEqualTo(3);
        assertThat(after.getPaymentRate()).isEqualTo(75.0);
        assertThat(after.getOnTimePaymentRate()).isEqualTo(25.0);     // e kechikib to'ladi
        assertThat(inTx(() -> monthlyRepo.findByTeacherIdAndMonthStart(teacher, d("01.09.2026")))
            .orElseThrow().getSource()).isEqualTo(TeacherKpiMonthly.SOURCE_MANUAL);
        assertThat(inTx(() -> monthlyRepo.findByMonthStart(d("01.09.2026")))).hasSize(1);
    }

    @Test
    void recompute_removesDuplicateRows_fromDatabaseWithoutUnique() {
        // pgtest da V74 UNIQUE bor — dublikat qo'yib bo'lmaydi (u holat TeacherKpiUniqueMigrationTest da)
        org.junit.jupiter.api.Assumptions.assumeFalse(Boolean.TRUE.equals(jdbc.execute(
            (org.springframework.jdbc.core.ConnectionCallback<Boolean>) c ->
                c.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT).contains("postgres"))),
            "faqat UNIQUE siz baza (H2)");
        september();
        clock.setDate(d("01.10.2026"));
        snapshots.monthly();
        // UNIQUE yo'q bazadagi holat (H2 da V72/V74 yo'q): shu o'qituvchi + oy uchun eskiroq ikkinchi qator
        inTx(() -> {
            TeacherKpiMonthly first = monthlyRepo.findByTeacherIdAndMonthStart(teacher, d("01.09.2026")).orElseThrow();
            monthlyRepo.save(TeacherKpiMonthly.builder().teacherId(teacher).monthStart(first.getMonthStart())
                .attendancePresent(0).attendanceTotal(0).periodsDecided(0).periodsPaid(0).periodsOnTime(0)
                .periodsPending(0).openAtEnd(0).graduated(0).churned(0).insufficientData(true).groupCount(0)
                .studentCount(0).source(TeacherKpiMonthly.SOURCE_JOB)
                .computedAt(first.getComputedAt().minusDays(1)).build());
        });
        assertThat(inTx(() -> monthlyRepo.findByMonthStart(d("01.09.2026")))).hasSize(2);
        // O'qishda eng yangisi (computed_at) — dublikat natijani buzmaydi
        assertThat(kpi.scoresForMonth(teacher, kpi.resolveMonth("2026-09")).getPaymentRate()).isEqualTo(50.0);

        clock.setDate(d("02.10.2026"));
        snapshots.recompute(YearMonth.of(2026, 9));
        assertThat(inTx(() -> monthlyRepo.findByMonthStart(d("01.09.2026")))).singleElement()
            .satisfies(m -> assertThat(m.getSource()).isEqualTo(TeacherKpiMonthly.SOURCE_MANUAL));
    }

    @Test
    void closedMonthWithoutSnapshot_fallsBackToLive_andFutureMonthRejected() {
        september();
        clock.setDate(d("05.10.2026"));
        TeacherKpiScoresDto s = kpi.scoresForMonth(teacher, kpi.resolveMonth("2026-09"));
        assertThat(s.getSource()).isEqualTo(TeacherKpiService.SOURCE_LIVE);
        assertThat(s.getPeriodsDecided()).isEqualTo(4);

        assertThatThrownBy(() -> kpi.resolveMonth("2026-11")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> kpi.resolveMonth("2026/09")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> snapshots.recompute(YearMonth.of(2026, 10))).isInstanceOf(BadRequestException.class);
    }
}
