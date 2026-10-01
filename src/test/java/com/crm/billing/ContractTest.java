package com.crm.billing;

import com.crm.billing.support.AbstractBillingIT;
import com.crm.dto.request.BalanceAdjustRequest;
import com.crm.dto.request.BalanceTransferRequest;
import com.crm.dto.request.PaymentRequest;
import com.crm.dto.request.StudentGroupRequest;
import com.crm.dto.response.BalanceHistoryItemDto;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.BalanceTransactionType;
import com.crm.entity.enums.PaymentMethod;
import com.crm.entity.enums.UserRole;
import com.crm.exception.CodedException;
import com.crm.repository.StudentGroupRepository;
import com.crm.service.GroupService;
import com.crm.service.PaymentService;
import com.crm.service.StudentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 7-bosqich: §10.2 shartnomasi — balance-history, balance-adjust, balance-transfer, guruhga qo'shish. */
class ContractTest extends AbstractBillingIT {

    @Autowired
    StudentService students;
    @Autowired
    GroupService groups;
    @Autowired
    PaymentService payments;
    @Autowired
    AccrualService accrual;
    @Autowired
    StudentGroupRepository sgRepo;

    private static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run)
            .isInstanceOf(CodedException.class)
            .extracting(e -> ((CodedException) e).getCode())
            .isEqualTo(code);
    }

    @Test
    void balanceHistory_hasV2Fields() {
        Long student = fixtures.student();
        Long group = fixtures.group(fixtures.course(700_000));
        Long sg = fixtures.enrollment(student, group).start(d("15.09.2026")).save();
        accrual.accrueUpTo(sg, d("15.09.2026"));
        fixtures.loginAs(UserRole.ACCOUNTANT);
        PaymentRequest r = new PaymentRequest();
        r.setStudentId(student);
        r.setGroupId(group);
        r.setAmount(BigDecimal.valueOf(700_000));
        r.setCashRegisterId(fixtures.cashRegister(false));
        r.setPaymentMethod(PaymentMethod.CASH);
        String receipt = payments.createPayment(r, null).getReceiptNumber();

        List<BalanceHistoryItemDto> h = students.getBalanceHistory(student, null, null, null);

        BalanceHistoryItemDto charge = h.stream().filter(x -> x.getType() == BalanceTransactionType.PERIOD_CHARGE)
            .findFirst().orElseThrow();
        assertThat(charge.getEffectiveDate()).isEqualTo(d("15.09.2026"));
        assertThat(charge.getBillingPeriod().start()).isEqualTo(d("15.09.2026"));
        assertThat(charge.getBillingPeriod().end()).isEqualTo(d("14.10.2026"));
        BalanceHistoryItemDto pay = h.stream().filter(x -> x.getType() == BalanceTransactionType.PAYMENT)
            .findFirst().orElseThrow();
        assertThat(pay.getPaymentId()).isNotNull();
        assertThat(pay.getReceiptNumber()).isEqualTo(receipt);
        assertThat(pay.getId()).isNotNull();
    }

    @Test
    @WithMockUser(roles = "ACCOUNTANT")
    void balanceHistory_openToAccountant_transferOnlySa() throws Exception {
        Long student = fixtures.student();
        mvc.perform(get("/api/students/" + student + "/balance-history")).andExpect(status().isOk());
        mvc.perform(post("/api/students/" + student + "/balance-transfer").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromGroupId\":1,\"toGroupId\":2,\"amount\":1000,\"note\":\"abc\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void balanceAdjust_wholeSumAndEffectiveDate() {
        Long student = fixtures.student();
        Long group = fixtures.group(fixtures.course(700_000));
        Long sg = fixtures.enrollment(student, group).start(d("15.10.2026")).save();
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        BalanceAdjustRequest r = new BalanceAdjustRequest();
        r.setGroupId(group);
        r.setNote("Tuzatish");
        r.setAmount(new BigDecimal("100.50"));
        assertCode(() -> students.adjustBalance(student, r), "money.wholeSumRequired");
        r.setAmount(BigDecimal.valueOf(-50_000));
        r.setEffectiveDate(d("10.09.2026"));
        BalanceHistoryItemDto res = students.adjustBalance(student, r);
        assertThat(res.getEffectiveDate()).isEqualTo(d("10.09.2026"));
        StudentGroup after = inTx(() -> sgRepo.findById(sg).orElseThrow());
        assertThat(after.getBalance()).isEqualByComparingTo("-50000");
        assertThat(after.getDebtSince()).isEqualTo(d("10.09.2026"));
    }

    @Test
    void balanceTransfer_movesBetweenOwnGroups() {
        Long student = fixtures.student();
        Long a = fixtures.group(fixtures.course(700_000));
        Long b = fixtures.group(fixtures.course(500_000));
        Long sgA = fixtures.enrollment(student, a).start(d("15.10.2026")).save();
        Long sgB = fixtures.enrollment(student, b).start(d("15.10.2026")).save();
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        BalanceAdjustRequest adj = new BalanceAdjustRequest();
        adj.setGroupId(a);
        adj.setAmount(BigDecimal.valueOf(300_000));
        adj.setNote("Boshlang'ich");
        students.adjustBalance(student, adj);

        BalanceTransferRequest r = new BalanceTransferRequest();
        r.setFromGroupId(a);
        r.setToGroupId(b);
        r.setAmount(BigDecimal.valueOf(200_000));
        r.setNote("Ota-ona so'rovi");
        List<BalanceHistoryItemDto> lines = students.transferBalance(student, r);

        assertThat(lines).extracting(BalanceHistoryItemDto::getType)
            .containsExactly(BalanceTransactionType.TRANSFER_OUT, BalanceTransactionType.TRANSFER_IN);
        assertThat(lines.get(1).getRelatedTxId()).isEqualTo(lines.get(0).getId());
        assertThat(inTx(() -> sgRepo.findById(sgA).orElseThrow()).getBalance()).isEqualByComparingTo("100000");
        assertThat(inTx(() -> sgRepo.findById(sgB).orElseThrow()).getBalance()).isEqualByComparingTo("200000");
        r.setToGroupId(a);
        assertCode(() -> students.transferBalance(student, r), "balanceTransfer.sameGroup");
    }

    /** §10.2: paymentStartDate ≤ bugun — darhol charge; §9.5 override; §3.5 chegirma 0..100. */
    @Test
    void addToGroup_chargesImmediately_noAutoOverride_validDiscount() {
        clock.setDate(d("20.09.2026"));
        Long student = fixtures.student();
        Long group = fixtures.group(fixtures.course(700_000));
        fixtures.loginAs(UserRole.ADMIN);
        StudentGroupRequest r = new StudentGroupRequest();
        r.setStudentId(student);
        r.setGroupId(group);
        r.setPaymentStartDate(d("20.09.2026"));
        r.setMonthlyFee(BigDecimal.valueOf(700_000));
        r.setDiscountPercentage(new BigDecimal("150"));
        assertCode(() -> groups.addStudentToGroup(r), "student.discount.invalid");

        r.setDiscountPercentage(null);
        groups.addStudentToGroup(r);

        StudentGroup sg = inTx(() -> sgRepo.findByStudentIdAndGroupIdAndIsActiveTrue(student, group).orElseThrow());
        assertThat(sg.getMonthlyPriceOverride()).isNull();
        assertThat(sg.getDiscountPercentage()).isEqualByComparingTo("0");
        assertThat(sg.getBalance()).isEqualByComparingTo("-700000");
        assertThat(sg.getPaymentStatus().name()).isEqualTo("PENDING");
    }

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    void refreshSnapshots_endpoint() throws Exception {
        fixtures.student();
        mvc.perform(post("/api/admin/billing/refresh-snapshots"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.refreshed").value(1));
    }
}
