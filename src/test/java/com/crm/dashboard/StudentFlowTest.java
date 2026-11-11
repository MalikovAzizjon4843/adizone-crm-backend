package com.crm.dashboard;

import com.crm.billing.AccrualService;
import com.crm.billing.support.AbstractBillingIT;
import com.crm.dashboard.StudentFlowDtos.FlowRow;
import com.crm.dashboard.StudentFlowDtos.Report;
import com.crm.dashboard.StudentFlowDtos.StudentRow;
import com.crm.dto.request.FreezeStudentRequest;
import com.crm.dto.request.PaymentRequest;
import com.crm.dto.request.TransferGroupRequest;
import com.crm.entity.enums.ExitReasonCode;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.PaymentMethod;
import com.crm.entity.enums.UserRole;
import com.crm.exception.CodedException;
import com.crm.service.GroupService;
import com.crm.service.PaymentService;
import com.crm.service.StudentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** O'quvchi oqimi va retention (GET /api/analytics/student-flow): har kategoriya, ko'chish, bitirish, retention. */
class StudentFlowTest extends AbstractBillingIT {

    @Autowired StudentFlowService flow;
    @Autowired GroupService groups;
    @Autowired StudentService students;
    @Autowired PaymentService payments;
    @Autowired AccrualService accrual;
    @Autowired JdbcTemplate jdbc;

    Long t1;
    Long t2;
    Long g1;
    Long g2;
    Long g3;
    Long g4;
    Long sB;
    Long sC;
    Long sD;
    Long sE;

    @BeforeEach
    void resetCache() {
        flow.invalidate();
    }

    private void leave(Long student, Long group, String date, String reason, ExitReasonCode code, String notes) {
        clock.setDate(d(date));
        fixtures.loginAs(UserRole.ADMIN);
        groups.removeStudentFromGroup(group, student, reason, notes, code);
    }

    private void pay(Long student, Long group, long amount, String date) {
        clock.setDate(d(date));
        fixtures.loginAs(UserRole.ACCOUNTANT);
        PaymentRequest r = new PaymentRequest();
        r.setStudentId(student);
        r.setGroupId(group);
        r.setAmount(BigDecimal.valueOf(amount));
        r.setCashRegisterId(fixtures.cashRegister(false));
        r.setPaymentMethod(PaymentMethod.CASH);
        payments.createPayment(r, null);
    }

    /**
     * Oktyabr 2026. T1: G1 (a qoladi, b PRICE bilan ketadi, c sababsiz ketadi, d G3 ga ko'chadi, e 05.10 da
     * qo'shiladi, h — sinov), G2 (f bitiradi, g muzlatiladi). T2: G3 (d ko'chib keladi), G4 — 25.10 da tugagan
     * guruh (i ochiq qolgan, j tugagandan keyin "LEFT" bilan chiqarilgan).
     */
    private void october() {
        t1 = fixtures.teacher();
        t2 = fixtures.teacher();
        g1 = fixtures.group(fixtures.course(700_000), GroupStatus.ACTIVE, t1);
        g2 = fixtures.group(fixtures.course(500_000), GroupStatus.ACTIVE, t1);
        g3 = fixtures.group(fixtures.course(700_000), GroupStatus.ACTIVE, t2);
        g4 = fixtures.group(fixtures.course(600_000), GroupStatus.COMPLETED, t2);
        jdbc.update("UPDATE groups SET end_date = ? WHERE id = ?", d("25.10.2026"), g4);

        Long a = fixtures.student();
        fixtures.enrollment(a, g1).start(d("01.09.2026")).save();
        sB = fixtures.student();
        fixtures.enrollment(sB, g1).start(d("01.09.2026")).save();
        sC = fixtures.student();
        fixtures.enrollment(sC, g1).start(d("01.09.2026")).save();
        sD = fixtures.student();
        fixtures.enrollment(sD, g1).start(d("01.09.2026")).save();
        sE = fixtures.student();
        fixtures.enrollment(sE, g1).start(d("05.10.2026")).save();
        Long h = fixtures.student();
        fixtures.enrollment(h, g1).start(d("03.10.2026")).trial().save();
        Long f = fixtures.student();
        fixtures.enrollment(f, g2).start(d("01.09.2026")).save();
        Long g = fixtures.student();
        fixtures.enrollment(g, g2).start(d("01.09.2026")).save();
        Long i = fixtures.student();
        fixtures.enrollment(i, g4).start(d("01.09.2026")).save();
        Long j = fixtures.student();
        Long sgJ = fixtures.enrollment(j, g4).start(d("01.09.2026")).save();
        jdbc.update("UPDATE student_groups SET is_active = FALSE, leave_date = ?, exit_date = ?, exit_reason = 'LEFT',"
            + " exit_reason_code = 'OTHER' WHERE id = ?", d("28.10.2026"), d("28.10.2026"), sgJ);

        leave(sB, g1, "10.10.2026", "LEFT", ExitReasonCode.PRICE, null);
        leave(sC, g1, "12.10.2026", null, null, null);

        clock.setDate(d("15.10.2026"));
        fixtures.loginAs(UserRole.ADMIN);
        TransferGroupRequest tr = new TransferGroupRequest();
        tr.setFromGroupId(g1);
        tr.setToGroupId(g3);
        students.transferGroup(sD, tr);

        clock.setDate(d("18.10.2026"));
        fixtures.loginAs(UserRole.ADMIN);
        FreezeStudentRequest fr = new FreezeStudentRequest();
        fr.setGroupId(g2);
        fr.setNote("Ta'til");
        students.freezeStudent(g, fr);

        leave(f, g2, "20.10.2026", "GRADUATED", ExitReasonCode.GRADUATED, null);
        clock.setDate(d("05.11.2026"));
    }

    private static FlowRow teacher(Report r, Long teacherId) {
        return r.teachers().stream().filter(t -> teacherId.equals(t.teacherId())).findFirst().orElseThrow();
    }

    private static FlowRow group(FlowRow teacher, Long groupId) {
        return teacher.groups().stream().filter(g -> groupId.equals(g.groupId())).findFirst().orElseThrow();
    }

    @Test
    void everyCategory_byTeacherAndGroup_transferIsNotLoss_completedIsNotLoss() {
        october();
        Report r = flow.report("2026-10", null, null);

        assertThat(r.monthFinal()).isTrue();
        assertThat(r.asOf()).isEqualTo(d("31.10.2026"));

        FlowRow gr1 = group(teacher(r, t1), g1);
        assertThat(gr1.startActive()).isEqualTo(4);          // a, b, c, d (h — sinov, e — keyin qo'shilgan)
        assertThat(gr1.joined()).isEqualTo(1);               // e
        assertThat(gr1.transferredIn()).isZero();
        assertThat(gr1.leftWithReason()).isEqualTo(1);       // b — PRICE
        assertThat(gr1.leftByReason()).containsEntry("PRICE", 1L).hasSize(1);
        assertThat(gr1.leftNoReason()).isEqualTo(1);         // c
        assertThat(gr1.transferredOut()).isEqualTo(1);       // d — yo'qotish emas
        assertThat(gr1.lost()).isEqualTo(2);
        assertThat(gr1.endActive()).isEqualTo(2);            // a, e
        assertThat(gr1.potential()).isEqualTo(4 + 1 + 0 - 0 - 1);
        assertThat(gr1.lostRevenue()).isEqualByComparingTo("1400000");

        FlowRow gr2 = group(teacher(r, t1), g2);
        assertThat(gr2.startActive()).isEqualTo(2);
        assertThat(gr2.completed()).isEqualTo(1);            // f — GRADUATED
        assertThat(gr2.frozen()).isEqualTo(1);               // g
        assertThat(gr2.lost()).isZero();
        assertThat(gr2.lostRevenue()).isEqualByComparingTo("0");
        assertThat(gr2.endActive()).isZero();
        assertThat(gr2.potential()).isEqualTo(1);

        FlowRow teacher1 = teacher(r, t1);
        assertThat(teacher1.startActive()).isEqualTo(6);
        assertThat(teacher1.lost()).isEqualTo(2);
        assertThat(teacher1.potential()).isEqualTo(6 + 1 - 1 - 1);
        assertThat(teacher1.groups()).hasSize(2);

        FlowRow teacher2 = teacher(r, t2);
        FlowRow gr3 = group(teacher2, g3);
        assertThat(gr3.transferredIn()).isEqualTo(1);        // d — ko'chib keldi, "yangi" emas
        assertThat(gr3.joined()).isZero();
        assertThat(gr3.endActive()).isEqualTo(1);
        FlowRow gr4 = group(teacher2, g4);
        assertThat(gr4.startActive()).isEqualTo(2);
        assertThat(gr4.completed()).isEqualTo(2);            // i — ochiq qolgan, j — tugagandan keyin chiqarilgan
        assertThat(gr4.lost()).isZero();
        assertThat(gr4.endActive()).isZero();

        FlowRow total = r.total();
        assertThat(total.startActive()).isEqualTo(8);
        assertThat(total.joined()).isEqualTo(1);
        assertThat(total.transferredIn()).isEqualTo(1);
        assertThat(total.transferredOut()).isEqualTo(1);
        assertThat(total.completed()).isEqualTo(3);
        assertThat(total.frozen()).isEqualTo(1);
        assertThat(total.lost()).isEqualTo(2);
        assertThat(total.potential()).isEqualTo(8 + 1 + 1 - 3 - 1);
        assertThat(total.endActive()).isEqualTo(3);
        // balans: oxiri = boshi + kirish − chiqishlar
        assertThat(total.endActive()).isEqualTo(total.potential() - total.lost() - total.frozen());
        assertThat(total.lostRevenue()).isEqualByComparingTo("1400000");

        // Filtr: o'qituvchi va guruh
        Report onlyT2 = flow.report("2026-10", t2, null);
        assertThat(onlyT2.teachers()).extracting(FlowRow::teacherId).containsExactly(t2);
        assertThat(onlyT2.total().transferredIn()).isEqualTo(1);
        assertThat(onlyT2.total().lost()).isZero();
        Report onlyG1 = flow.report("2026-10", null, g1);
        assertThat(onlyG1.total().startActive()).isEqualTo(4);
        assertThat(onlyG1.teachers().get(0).groups()).extracting(FlowRow::groupId).containsExactly(g1);
    }

    @Test
    void drillDown_lostAndCategories() {
        october();

        List<StudentRow> lost = flow.students("2026-10", t1, null, "lost");
        assertThat(lost).extracting(StudentRow::studentId).containsExactly(sB, sC);
        StudentRow b = lost.get(0);
        assertThat(b.category()).isEqualTo("leftWithReason");
        assertThat(b.date()).isEqualTo(d("10.10.2026"));
        assertThat(b.reasonCode()).isEqualTo("PRICE");
        assertThat(b.reasonLabel()).isEqualTo(ExitReasonCode.PRICE.getLabel());
        assertThat(b.groupId()).isEqualTo(g1);
        assertThat(b.teacherId()).isEqualTo(t1);
        assertThat(b.teacherName()).isNotBlank();
        assertThat(b.monthlyPrice()).isEqualByComparingTo("700000");
        assertThat(lost.get(1).category()).isEqualTo("leftNoReason");
        assertThat(lost.get(1).reasonCode()).isEqualTo("OTHER");

        assertThat(flow.students("2026-10", null, null, "transferredIn"))
            .singleElement().satisfies(row -> {
                assertThat(row.studentId()).isEqualTo(sD);
                assertThat(row.groupId()).isEqualTo(g3);
                assertThat(row.date()).isEqualTo(d("15.10.2026"));
            });
        assertThat(flow.students("2026-10", null, null, "transferredOut"))
            .extracting(StudentRow::studentId).containsExactly(sD);
        assertThat(flow.students("2026-10", null, null, "joined"))
            .extracting(StudentRow::studentId).containsExactly(sE);
        assertThat(flow.students("2026-10", null, null, "completed")).hasSize(3);
        assertThat(flow.students("2026-10", null, null, "frozen")).hasSize(1);
        assertThat(flow.students("2026-10", null, g1, "leftNoReason"))
            .extracting(StudentRow::studentId).containsExactly(sC);

        assertThatThrownBy(() -> flow.students("2026-10", null, null, "nimadir"))
            .isInstanceOf(CodedException.class);
    }

    @Test
    void retention_nextMonth_realPayments_andNotFinalWhileNextMonthOpen() {
        Long g = fixtures.group(fixtures.course(700_000), GroupStatus.ACTIVE, fixtures.teacher());
        Long x = fixtures.student();
        Long sgX = fixtures.enrollment(x, g).start(d("01.10.2026")).save();
        Long y = fixtures.student();
        Long sgY = fixtures.enrollment(y, g).start(d("01.10.2026")).save();
        Long z = fixtures.student();                         // oktyabr to'lanmagan — bazaga kirmaydi
        Long sgZ = fixtures.enrollment(z, g).start(d("01.10.2026")).save();
        clock.setDate(d("01.10.2026"));
        accrual.accrueUpTo(sgX, d("01.10.2026"));
        accrual.accrueUpTo(sgY, d("01.10.2026"));
        accrual.accrueUpTo(sgZ, d("01.10.2026"));
        pay(x, g, 700_000, "01.10.2026");
        pay(y, g, 700_000, "02.10.2026");
        clock.setDate(d("01.11.2026"));
        accrual.accrueUpTo(sgX, d("01.11.2026"));
        accrual.accrueUpTo(sgY, d("01.11.2026"));
        pay(x, g, 700_000, "03.11.2026");                    // x — noyabrda ham to'ladi, y — yo'q

        clock.setDate(d("15.11.2026"));
        StudentFlowDtos.Retention open = flow.report("2026-10", null, null).total().retentionNextMonth();
        assertThat(open.paidStudents()).isEqualTo(2);
        assertThat(open.retainedStudents()).isEqualTo(1);
        assertThat(open.percent()).isEqualByComparingTo("50.0");
        assertThat(open.finalized()).isFalse();             // noyabr hali tugamagan

        clock.setDate(d("01.12.2026"));
        StudentFlowDtos.Retention closed = flow.report("2026-10", null, null).total().retentionNextMonth();
        assertThat(closed.finalized()).isTrue();
        assertThat(closed.percent()).isEqualByComparingTo("50.0");

        // Joriy oy: hisob bugungacha, oy tugamagan
        Report current = flow.report(null, null, null);
        assertThat(current.month()).isEqualTo("2026-12");
        assertThat(current.monthFinal()).isFalse();
        assertThat(current.asOf()).isEqualTo(d("01.12.2026"));
    }

    @Test
    void api_permissions_and_monthValidation() throws Exception {
        october();
        mvc.perform(get("/api/analytics/student-flow").param("month", "2026-10")
                .with(user("t").roles("TEACHER")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/analytics/student-flow/students").param("month", "2026-10").param("category", "lost")
                .with(user("t").roles("TEACHER")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/analytics/student-flow").param("month", "2026-10")
                .with(user("acc").roles("ACCOUNTANT")))
            .andExpect(status().isForbidden());

        mvc.perform(get("/api/analytics/student-flow").param("month", "2026-10")
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.month").value("2026-10"))
            .andExpect(jsonPath("$.data.total.lost").value(2))
            .andExpect(jsonPath("$.data.total.retentionNextMonth.final").value(false))
            .andExpect(jsonPath("$.data.teachers[0].groups").isArray());
        mvc.perform(get("/api/analytics/student-flow/students").param("month", "2026-10").param("category", "lost")
                .with(user("sa").roles("SUPER_ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].reasonCode").value("PRICE"));

        mvc.perform(get("/api/analytics/student-flow").param("month", "2026-13")
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/analytics/student-flow").param("month", "2027-01")
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/analytics/student-flow/students").param("month", "2026-10").param("category", "x")
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isBadRequest());
    }
}
