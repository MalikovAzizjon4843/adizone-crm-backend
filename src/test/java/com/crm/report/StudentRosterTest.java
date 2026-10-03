package com.crm.report;

import com.crm.billing.AccrualService;
import com.crm.billing.support.AbstractBillingIT;
import com.crm.dto.request.FreezeStudentRequest;
import com.crm.dto.request.PaymentRequest;
import com.crm.dto.response.FrozenStudentResponse;
import com.crm.dto.response.LeftStudentResponse;
import com.crm.entity.enums.ExitReasonCode;
import com.crm.entity.enums.PaymentMethod;
import com.crm.entity.enums.StudentStatus;
import com.crm.entity.enums.UserRole;
import com.crm.service.GroupService;
import com.crm.service.PaymentService;
import com.crm.service.StudentRosterService;
import com.crm.service.StudentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** "Chiqib ketganlar" (oxirgi guruh, sana, sabab) va "Muzlatilganlar" (har yozilma, ledger qoldig'i). */
class StudentRosterTest extends AbstractBillingIT {

    @Autowired GroupService groups;
    @Autowired StudentService students;
    @Autowired PaymentService payments;
    @Autowired AccrualService accrual;
    @Autowired StudentRosterService roster;

    record Ids(Long student, Long group, Long sg) {
    }

    /** 700 000 × 10% = 630 000 / oy. */
    private Ids monthly(Long student, String start) {
        Long group = fixtures.group(fixtures.course(700_000));
        Long sg = fixtures.enrollment(student, group).start(d(start)).discount("10").save();
        return new Ids(student, group, sg);
    }

    private void accrueOn(Ids ids, String date) {
        clock.setDate(d(date));
        accrual.accrueUpTo(ids.sg(), d(date));
    }

    private void pay(Ids ids, long amount, String date) {
        clock.setDate(d(date));
        fixtures.loginAs(UserRole.ACCOUNTANT);
        PaymentRequest r = new PaymentRequest();
        r.setStudentId(ids.student());
        r.setGroupId(ids.group());
        r.setAmount(BigDecimal.valueOf(amount));
        r.setCashRegisterId(fixtures.cashRegister(false));
        r.setPaymentMethod(PaymentMethod.CASH);
        payments.createPayment(r, null);
    }

    private void freeze(Ids ids, String date) {
        clock.setDate(d(date));
        fixtures.loginAs(UserRole.ADMIN);
        FreezeStudentRequest r = new FreezeStudentRequest();
        r.setGroupId(ids.group());
        r.setNote("Ta'til");
        students.freezeStudent(ids.student(), r);
    }

    private void leave(Ids ids, String date, String reason, ExitReasonCode code, String notes) {
        clock.setDate(d(date));
        fixtures.loginAs(UserRole.ADMIN);
        groups.removeStudentFromGroup(ids.group(), ids.student(), reason, notes, code);
    }

    // ── chiqib ketganlar ────────────────────────────────────────────────

    @Test
    void left_lastClosedGroup_leaveDate_reason() throws Exception {
        Long s = fixtures.student();
        Ids first = monthly(s, "01.09.2026");
        Ids last = monthly(s, "01.09.2026");
        leave(first, "10.09.2026", "LEFT", ExitReasonCode.SCHEDULE, null);
        leave(last, "20.09.2026", "LEFT", ExitReasonCode.PRICE, "Qimmat");

        LeftStudentResponse row = roster.getLeftStudents().stream()
            .filter(r -> r.getId().equals(s)).findFirst().orElseThrow();
        assertThat(row.getStatus()).isEqualTo(StudentStatus.LEFT);
        assertThat(row.getLastGroupId()).isEqualTo(last.group());
        assertThat(row.getLastGroupName()).isNotBlank();
        assertThat(row.getLastStudentGroupId()).isEqualTo(last.sg());
        assertThat(row.getLeaveDate()).isEqualTo(d("20.09.2026"));
        assertThat(row.getExitReason()).isEqualTo("LEFT");
        assertThat(row.getExitReasonCode()).isEqualTo(ExitReasonCode.PRICE);
        assertThat(row.getExitNotes()).isEqualTo("Qimmat");

        // JSON: front kalitlari (id, exitReason) va yangi maydonlar
        mvc.perform(get("/api/students/left").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].id").value(s))
            .andExpect(jsonPath("$.data[0].lastGroupId").value(last.group()))
            .andExpect(jsonPath("$.data[0].leaveDate").value("2026-09-20"))
            .andExpect(jsonPath("$.data[0].exitReason").value("LEFT"))
            .andExpect(jsonPath("$.data[0].exitReasonCode").value("PRICE"));
    }

    @Test
    void left_graduated_and_sortedNewestFirst_reasonFallsBackToCode() {
        Long a = fixtures.student();
        Ids aSg = monthly(a, "01.09.2026");
        leave(aSg, "05.09.2026", "GRADUATED", null, null);
        Long b = fixtures.student();
        Ids bSg = monthly(b, "01.09.2026");
        // Matnsiz sabab, faqat kod (yangi front) — oxirgi yozilma yopildi → LEFT (StudentStatusService)
        clock.setDate(d("12.09.2026"));
        fixtures.loginAs(UserRole.ADMIN);
        groups.removeStudentFromGroup(bSg.group(), b, null, null, ExitReasonCode.MOVED_AWAY);

        List<LeftStudentResponse> rows = roster.getLeftStudents();
        assertThat(rows).extracting(LeftStudentResponse::getId).containsExactly(b, a);
        assertThat(rows.get(0).getExitReason()).isEqualTo("MOVED_AWAY");
        assertThat(rows.get(1).getStatus()).isEqualTo(StudentStatus.GRADUATED);
        assertThat(rows.get(1).getExitReason()).isEqualTo("GRADUATED");
        assertThat(rows.get(1).getLeaveDate()).isEqualTo(d("05.09.2026"));
    }

    // ── muzlatilganlar ──────────────────────────────────────────────────

    @Test
    void frozen_perEnrollment_ledgerRemaining_andTotals() {
        // To'lagan: 630 000 − qaytarim 357 000 dan keyin qoldiq +357 000
        Long paid = fixtures.student();
        Ids p = monthly(paid, "15.09.2026");
        accrueOn(p, "15.09.2026");
        pay(p, 630_000, "16.09.2026");
        freeze(p, "28.09.2026");

        // To'lamagan: −630 000 + 357 000 = −273 000 (qarz)
        Long debtor = fixtures.student();
        Ids q = monthly(debtor, "15.09.2026");
        accrueOn(q, "15.09.2026");
        freeze(q, "28.09.2026");

        List<FrozenStudentResponse> rows = roster.getFrozenStudents();
        FrozenStudentResponse pr = rows.stream().filter(r -> r.getStudentId().equals(paid)).findFirst().orElseThrow();
        assertThat(pr.getStudentStatus()).isEqualTo(StudentStatus.FROZEN);
        assertThat(pr.getEnrollments()).singleElement().satisfies(e -> {
            assertThat(e.getStudentGroupId()).isEqualTo(p.sg());
            assertThat(e.getGroupId()).isEqualTo(p.group());
            assertThat(e.getFrozenFrom()).isEqualTo(d("28.09.2026"));
            assertThat(e.getBalance()).isEqualByComparingTo("357000");
            assertThat(e.getRemainingAmount()).isEqualByComparingTo("357000");
            assertThat(e.getDebt()).isEqualByComparingTo("0");
            assertThat(e.getNote()).isEqualTo("Ta'til");
        });
        assertThat(pr.getTotalRemaining()).isEqualByComparingTo("357000");
        assertThat(pr.getBalance()).isEqualByComparingTo("357000");
        assertThat(pr.getFrozenDate()).isEqualTo(d("28.09.2026"));
        assertThat(pr.getLastGroupId()).isEqualTo(p.group());

        FrozenStudentResponse dr = rows.stream().filter(r -> r.getStudentId().equals(debtor)).findFirst().orElseThrow();
        assertThat(dr.getEnrollments()).singleElement().satisfies(e -> {
            assertThat(e.getBalance()).isEqualByComparingTo("-273000");
            assertThat(e.getRemainingAmount()).isEqualByComparingTo("0");
            assertThat(e.getDebt()).isEqualByComparingTo("273000");
        });
        assertThat(dr.getTotalDebt()).isEqualByComparingTo("273000");
    }

    @Test
    void frozen_onlyFrozenEnrollmentCounted_notStudentWideBalance() {
        Long s = fixtures.student();
        Ids frozen = monthly(s, "15.09.2026");
        Ids active = monthly(s, "15.09.2026");
        accrueOn(frozen, "15.09.2026");
        pay(frozen, 630_000, "16.09.2026");
        accrueOn(active, "16.09.2026");
        pay(active, 1_000_000, "16.09.2026");        // faol guruhda +370 000 ortiqcha
        freeze(frozen, "28.09.2026");

        FrozenStudentResponse row = roster.getFrozenStudents().stream()
            .filter(r -> r.getStudentId().equals(s)).findFirst().orElseThrow();
        assertThat(row.getStudentStatus()).isEqualTo(StudentStatus.ACTIVE);   // boshqa guruhda o'qiydi
        assertThat(row.getEnrollments()).extracting(FrozenStudentResponse.FrozenEnrollment::getStudentGroupId)
            .containsExactly(frozen.sg());
        // students.balance (357 000 + 370 000) emas — faqat muzlatilgan yozilma ledgeri
        assertThat(row.getTotalRemaining()).isEqualByComparingTo("357000");
        assertThat(row.getBalance()).isEqualByComparingTo("357000");
    }
}
