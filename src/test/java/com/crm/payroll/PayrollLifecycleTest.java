package com.crm.payroll;

import com.crm.dto.request.PayrollPayDto;
import com.crm.dto.response.PayrollGenerateResult;
import com.crm.dto.response.PayrollResponse;
import com.crm.entity.CashRegister;
import com.crm.entity.CashTransaction;
import com.crm.entity.enums.CashTransactionType;
import com.crm.entity.enums.PayrollStatus;
import com.crm.entity.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Payroll v2 — qaror 1 (holatlar), 2 (generate), 4 (DELETE/cancel/PUT). */
class PayrollLifecycleTest extends PayrollItBase {

    /** O'qituvchi + sentyabrda to'lagan bitta o'quvchi: gross = 3 000 000 + 100 000. */
    private Staff teacherWithPaidStudent() {
        Staff t = teacherStaff();
        teacherRule(t, 3_000_000, 100_000);
        pay(monthly(t.teacherId(), 700_000, "15.09.2026"), 700_000, "15.09.2026");
        return t;
    }

    private Map<Long, String> skippedReasons(PayrollGenerateResult r) {
        return r.skipped().stream().filter(s -> s.userId() != null)
            .collect(Collectors.toMap(PayrollGenerateResult.Skipped::userId, PayrollGenerateResult.Skipped::reason));
    }

    // ── Qaror 1: DRAFT → APPROVED → PAID ───────────────────────────────

    @Test
    void lifecycle_draftApprovedPaid_illegalTransitionsRejected() {
        Staff t = teacherWithPaidStudent();
        PayrollResponse draft = draftFor(t.userId(), SEP);
        assertThat(draft.getStatus()).isEqualTo(PayrollStatus.DRAFT);
        assertThat(draft.getNetSalary()).isEqualByComparingTo("3100000");
        Long id = draft.getId();

        assertCode(() -> payroll.markAsPaid(id, null, null), "payroll.notApproved");
        clock.setDate(d("01.10.2026"));
        PayrollResponse approved = payroll.approve(id, null);
        assertThat(approved.getStatus()).isEqualTo(PayrollStatus.APPROVED);
        assertThat(approved.getApprovedAt()).isNotNull();
        assertCode(() -> payroll.approve(id, null), "payroll.notDraft");
        assertCode(() -> payroll.recalculate(id), "payroll.notDraft");

        PayrollResponse paid = payroll.markAsPaid(id, null, null);
        assertThat(paid.getStatus()).isEqualTo(PayrollStatus.PAID);
        assertThat(paid.getPaymentDate()).isEqualTo(d("01.10.2026"));
        assertThat(paid.getPaidAt()).isNotNull();
        assertCode(() -> payroll.approve(id, null), "payroll.notDraft");
        assertCode(() -> payroll.markAsPaid(id, null, null), "payroll.alreadyPaid");
    }

    @Test
    void approvedAndPaid_areNeverRewritten_byGenerateOrRecalculate() {
        Staff a = teacherWithPaidStudent();
        Staff b = teacherWithPaidStudent();
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        payroll.generatePayroll(SEP, YEAR, false);
        Long aId = payrollOf(a.userId(), SEP).getId();
        Long bId = payrollOf(b.userId(), SEP).getId();
        payroll.approve(aId, null);
        payroll.approve(bId, null);
        payroll.markAsPaid(bId, null, null);
        String aDetails = payroll(aId).getCalculationDetails();

        // Shu oyga yangi to'lagan o'quvchi — preview o'zgaradi, tasdiqlanganlar emas
        pay(monthly(a.teacherId(), 700_000, "20.09.2026"), 700_000, "20.09.2026");
        pay(monthly(b.teacherId(), 700_000, "20.09.2026"), 700_000, "20.09.2026");
        assertThat(calculator.calculateForUser(a.userId(), SEP, YEAR).getPaidStudentCount()).isEqualTo(2);

        fixtures.loginAs(UserRole.SUPER_ADMIN);
        PayrollGenerateResult r = payroll.generatePayroll(SEP, YEAR, true);
        assertThat(r.created()).isZero();
        assertThat(r.recalculated()).isZero();
        assertThat(skippedReasons(r)).containsEntry(a.userId(), "APPROVED").containsEntry(b.userId(), "PAID");
        assertThat(payroll(aId).getNetSalary()).isEqualByComparingTo("3100000");
        assertThat(payroll(aId).getCalculationDetails()).isEqualTo(aDetails);
        assertThat(payroll(bId).getPaidStudentCount()).isEqualTo(1);
        assertCode(() -> payroll.recalculate(aId), "payroll.notDraft");
    }

    // ── Qaror 2: generate yetishmayotganlarga, DRAFT faqat recalculate bilan ──

    @Test
    void generate_addsMissingStaff_andKeepsExistingDraft() {
        Staff a = teacherWithPaidStudent();
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        PayrollGenerateResult first = payroll.generatePayroll(SEP, YEAR, false);
        assertThat(first.created()).isEqualTo(1);
        Long aId = payrollOf(a.userId(), SEP).getId();

        // Yangi xodim va A uchun yangi ma'lumot — avval "Bu oy uchun oylik yaratilgan" bloki edi
        Staff b = teacherWithPaidStudent();
        pay(monthly(a.teacherId(), 700_000, "20.09.2026"), 700_000, "20.09.2026");
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        PayrollGenerateResult second = payroll.generatePayroll(SEP, YEAR, false);

        assertThat(second.created()).isEqualTo(1);
        assertThat(payrollOf(b.userId(), SEP).getStatus()).isEqualTo(PayrollStatus.DRAFT);
        assertThat(skippedReasons(second)).containsEntry(a.userId(), "DRAFT_EXISTS");
        assertThat(payroll(aId).getPaidStudentCount()).isEqualTo(1);
        assertThat(payroll(aId).getNetSalary()).isEqualByComparingTo("3100000");
    }

    @Test
    void generate_withRecalculate_updatesOnlyDrafts() {
        Staff a = teacherWithPaidStudent();
        Long aId = draftFor(a.userId(), SEP).getId();
        pay(monthly(a.teacherId(), 700_000, "20.09.2026"), 700_000, "20.09.2026");

        fixtures.loginAs(UserRole.SUPER_ADMIN);
        PayrollGenerateResult r = payroll.generatePayroll(SEP, YEAR, true);
        assertThat(r.recalculated()).isEqualTo(1);
        assertThat(r.created()).isZero();
        assertThat(payroll(aId).getId()).isEqualTo(aId);
        assertThat(payroll(aId).getPaidStudentCount()).isEqualTo(2);
        assertThat(payroll(aId).getNetSalary()).isEqualByComparingTo("3200000");
        assertThat(r.totalAmount()).isEqualByComparingTo("3200000");
    }

    // ── Qaror 4: DELETE faqat DRAFT; cancel faqat SA, REVERSAL, audit; PUT yo'q ──

    @Test
    void delete_onlyDraft() {
        Staff a = teacherWithPaidStudent();
        Long id = draftFor(a.userId(), SEP).getId();
        payroll.approve(id, null);
        assertCode(() -> payroll.deletePayroll(id), "payroll.notDraft");

        Staff b = teacherWithPaidStudent();
        Long draftId = draftFor(b.userId(), SEP).getId();
        payroll.deletePayroll(draftId);
        assertThat(inTx(() -> payrollRepo.findById(draftId))).isEmpty();
    }

    @Test
    void cancelPaid_superAdminOnly_reasonRequired_cashReversed_audited() throws Exception {
        Staff a = teacherWithPaidStudent();
        Long id = draftFor(a.userId(), SEP).getId();
        payroll.approve(id, null);
        Long reg = fixtures.cashRegister(false);
        PayrollPayDto pay = new PayrollPayDto();
        pay.setCashRegisterId(reg);
        PayrollResponse paid = payroll.markAsPaid(id, pay, null);
        assertThat(paid.getCashTransactionId()).isNotNull();
        assertThat(register(reg).getCashBalance()).isEqualByComparingTo("-3100000");

        fixtures.loginAs(UserRole.ADMIN);
        assertCode(() -> payroll.cancel(id, "Xato hisob"), "payroll.cancel.forbidden");
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        assertCode(() -> payroll.cancel(id, " "), "payroll.cancel.reasonRequired");

        PayrollResponse cancelled = payroll.cancel(id, "Xato hisob");
        assertThat(cancelled.getStatus()).isEqualTo(PayrollStatus.CANCELLED);
        assertThat(cancelled.getCancelReason()).isEqualTo("Xato hisob");
        assertThat(register(reg).getCashBalance()).isEqualByComparingTo("0");
        List<CashTransaction> txs = inTx(() -> cashRepo.findAll().stream()
            .filter(t -> id.equals(t.getPayrollId())).toList());
        assertThat(txs).extracting(CashTransaction::getType)
            .containsExactlyInAnyOrder(CashTransactionType.EXPENSE, CashTransactionType.REVERSAL);
        CashTransaction reversal = txs.stream().filter(t -> t.getType() == CashTransactionType.REVERSAL)
            .findFirst().orElseThrow();
        assertThat(reversal.getRelatedTxId()).isEqualTo(paid.getCashTransactionId());
        assertCode(() -> payroll.cancel(id, "Yana"), "payroll.alreadyCancelled");
        assertThat(awaitAudits("PAYMENT_CANCEL")).isEqualTo(1);

        // CANCELLED dan keyin shu oy uchun yangi DRAFT yaratiladi
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        assertThat(payroll.generatePayroll(SEP, YEAR, false).created()).isEqualTo(1);
        assertThat(payrollOf(a.userId(), SEP).getStatus()).isEqualTo(PayrollStatus.DRAFT);
    }

    @Test
    void cancelDraft_rejected_useDelete() {
        Staff a = teacherWithPaidStudent();
        Long id = draftFor(a.userId(), SEP).getId();
        assertCode(() -> payroll.cancel(id, "Kerak emas"), "payroll.cancel.draft");
    }

    @Test
    void putUpsertAndManualCreate_areRemoved() throws Exception {
        Staff a = teacherWithPaidStudent();
        Long id = draftFor(a.userId(), SEP).getId();
        String body = "{\"teacherId\":1,\"month\":9,\"year\":2026,\"status\":\"PAID\",\"basicSalary\":1}";
        mvc.perform(put("/api/payroll/" + id).with(user("test-super_admin").roles("SUPER_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/payroll").with(user("test-super_admin").roles("SUPER_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isMethodNotAllowed());
        assertThat(payroll(id).getStatus()).isEqualTo(PayrollStatus.DRAFT);
    }

    @Test
    void pay_isIdempotent_byKey_andSecondPayRejected() {
        Staff a = teacherWithPaidStudent();
        Long id = draftFor(a.userId(), SEP).getId();
        payroll.approve(id, null);
        Long reg = fixtures.cashRegister(false);
        PayrollPayDto dto = new PayrollPayDto();
        dto.setCashRegisterId(reg);

        PayrollResponse first = payroll.markAsPaid(id, dto, "key-123");
        PayrollResponse replay = payroll.markAsPaid(id, dto, "key-123");
        assertThat(replay.getCashTransactionId()).isEqualTo(first.getCashTransactionId());
        assertThat(inTx(() -> cashRepo.findAll().stream().filter(t -> id.equals(t.getPayrollId())).count()))
            .isEqualTo(1);
        assertThat(register(reg).getCashBalance()).isEqualByComparingTo("-3100000");
        assertCode(() -> payroll.markAsPaid(id, dto, "other-key"), "payroll.alreadyPaid");
        assertCode(() -> payroll.markAsPaid(id, dto, null), "payroll.alreadyPaid");
    }

    private CashRegister register(Long id) {
        return inTx(() -> registerRepo.findById(id).orElseThrow());
    }

    private int awaitAudits(String action) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        Integer n = 0;
        while (System.currentTimeMillis() < deadline) {
            n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = ? AND entity_type = 'Payroll'", Integer.class, action);
            if (n != null && n > 0) {
                break;
            }
            Thread.sleep(50);
        }
        return n == null ? 0 : n;
    }
}
