package com.crm.report;

import com.crm.billing.AccrualService;
import com.crm.billing.support.AbstractBillingIT;
import com.crm.dto.request.ExpenseCreateDto;
import com.crm.dto.request.IncomeCreateDto;
import com.crm.dto.request.PaymentRequest;
import com.crm.dto.request.TransferDto;
import com.crm.dto.response.CashBalanceDto;
import com.crm.dto.response.CashChannelReportDto;
import com.crm.dto.response.CashChannelSummaryDto;
import com.crm.dto.response.CashTransactionDto;
import com.crm.dto.response.FinanceReportResponse;
import com.crm.entity.enums.PaymentMethod;
import com.crm.entity.enums.UserRole;
import com.crm.exception.BadRequestException;
import com.crm.exception.CodedException;
import com.crm.repository.CashRegisterRepository;
import com.crm.service.CashRegisterService;
import com.crm.service.FinanceService;
import com.crm.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Kassa balansi, tranzaksiyalari va moliya hisoboti to'lov usuli guruhlari bo'yicha. */
class CashByMethodTest extends AbstractBillingIT {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 15);

    @Autowired CashRegisterService cash;
    @Autowired FinanceService finance;
    @Autowired PaymentService payments;
    @Autowired AccrualService accrual;
    @Autowired CashRegisterRepository registerRepo;
    @Autowired JdbcTemplate jdbc;

    private void income(Long reg, PaymentMethod m, long amount, Long cashPart, Long cardPart) {
        IncomeCreateDto dto = new IncomeCreateDto();
        dto.setAmount(BigDecimal.valueOf(amount));
        dto.setPaymentMethod(m);
        dto.setCashPart(cashPart != null ? BigDecimal.valueOf(cashPart) : null);
        dto.setCardPart(cardPart != null ? BigDecimal.valueOf(cardPart) : null);
        dto.setTransactionDate(DAY);
        dto.setTransactionType("Boshqa kirim");
        cash.addIncome(reg, dto);
    }

    private void expense(Long reg, PaymentMethod m, long amount) {
        ExpenseCreateDto dto = new ExpenseCreateDto();
        dto.setAmount(BigDecimal.valueOf(amount));
        dto.setPaymentMethod(m);
        dto.setTransactionDate(DAY);
        cash.addExpense(reg, dto);
    }

    /**
     * Kirim: CASH 100k, CARD 200k, TERMINAL 300k, CLICK 400k, eski BANK 500k (V78 dan oldingi yozuv), OTHER 600k,
     * CASH_AND_CARD 700k (250k naqd + 450k karta), o'quvchi to'lovi CASH 630k va uning bekor qilinishi.
     * Chiqim: CASH 50k, TERMINAL 30k. O'tkazma: CARD 20k boshqa kassaga.
     */
    private Long[] scenario() {
        Long reg = fixtures.cashRegister(true);
        Long other = fixtures.cashRegister(false);
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        income(reg, PaymentMethod.CASH, 100_000, null, null);
        income(reg, PaymentMethod.CARD, 200_000, null, null);
        income(reg, PaymentMethod.TERMINAL, 300_000, null, null);
        income(reg, PaymentMethod.CLICK, 400_000, null, null);
        income(reg, PaymentMethod.TERMINAL, 500_000, null, null);
        jdbc.update("UPDATE cash_transactions SET payment_method = 'BANK' WHERE cash_register_id = ? AND amount = 500000", reg);
        income(reg, PaymentMethod.OTHER, 600_000, null, null);
        income(reg, PaymentMethod.CASH_AND_CARD, 700_000, 250_000L, 450_000L);
        expense(reg, PaymentMethod.CASH, 50_000);
        expense(reg, PaymentMethod.TERMINAL, 30_000);

        TransferDto t = new TransferDto();
        t.setFromCashRegisterId(reg);
        t.setToCashRegisterId(other);
        t.setAmount(BigDecimal.valueOf(20_000));
        t.setPaymentMethod(PaymentMethod.CARD);
        cash.transfer(t);

        Long student = fixtures.student();
        Long group = fixtures.group(fixtures.course(700_000));
        Long sg = fixtures.enrollment(student, group).start(DAY).discount("10").save();
        accrual.accrueUpTo(sg, DAY);
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        PaymentRequest r = new PaymentRequest();
        r.setStudentId(student);
        r.setGroupId(group);
        r.setAmount(BigDecimal.valueOf(630_000));
        r.setCashRegisterId(reg);
        r.setPaymentMethod(PaymentMethod.CASH);
        Long paymentId = payments.createPayment(r, null).getId();
        payments.cancelPayment(paymentId, "Xato");
        return new Long[]{reg, other};
    }

    private static CashChannelSummaryDto channel(List<CashChannelSummaryDto> rows, String c) {
        return rows.stream().filter(r -> c.equals(r.getChannel())).findFirst().orElseThrow();
    }

    @Test
    void balance_byMethod_splitsCashAndCard_onlineGrouped_reconciled() {
        Long reg = scenario()[0];
        CashBalanceDto b = cash.getBalance(reg);

        assertThat(b.getByMethod()).extracting(CashChannelSummaryDto::getChannel)
            .containsExactly("CASH", "CARD", "TERMINAL", "ONLINE", "OTHER");
        assertThat(channel(b.getByMethod(), "CASH").getNet()).isEqualByComparingTo("300000");    // 100+250−50 (+630−630)
        assertThat(channel(b.getByMethod(), "CARD").getNet()).isEqualByComparingTo("630000");    // 200+450−20
        assertThat(channel(b.getByMethod(), "TERMINAL").getNet()).isEqualByComparingTo("770000");   // 300 + eski BANK 500 − 30
        assertThat(channel(b.getByMethod(), "ONLINE").getNet()).isEqualByComparingTo("400000");
        assertThat(channel(b.getByMethod(), "ONLINE").getMethods()).containsExactly("CLICK", "PAYME", "UZUM");
        assertThat(channel(b.getByMethod(), "OTHER").getNet()).isEqualByComparingTo("600000");
        CashChannelSummaryDto c = channel(b.getByMethod(), "CASH");
        assertThat(c.getIncome()).isEqualByComparingTo("980000");
        assertThat(c.getIncomeReversed()).isEqualByComparingTo("630000");
        assertThat(c.getExpense()).isEqualByComparingTo("50000");
        assertThat(channel(b.getByMethod(), "CARD").getTransferOut()).isEqualByComparingTo("20000");

        // Saqlangan chelaklar bilan mos
        assertThat(b.getCashBalance()).isEqualByComparingTo("300000");
        assertThat(b.getPlasticBalance()).isEqualByComparingTo("2400000");
        assertThat(b.isReconciled()).isTrue();
        assertThat(b.getUnattributedCash()).isEqualByComparingTo("0");

        // Tranzaksiyasiz o'zgarish (boshlang'ich qoldiq) — guruhlarga taqsimlanmaydi, farq ko'rinadi
        inTx(() -> {
            var r = registerRepo.findById(reg).orElseThrow();
            r.setCashBalance(r.getCashBalance().add(BigDecimal.valueOf(5_000)));
            r.setBalance(r.getBalance().add(BigDecimal.valueOf(5_000)));
            registerRepo.save(r);
        });
        CashBalanceDto after = cash.getBalance(reg);
        assertThat(after.isReconciled()).isFalse();
        assertThat(after.getUnattributedCash()).isEqualByComparingTo("5000");
        assertThat(after.getUnattributedNonCash()).isEqualByComparingTo("0");

        // Ro'yxat: har kassada balanceByMethod
        Map<String, BigDecimal> listed = cash.getAll(null).stream()
            .filter(r -> r.getId().equals(reg)).findFirst().orElseThrow().getBalanceByMethod();
        assertThat(listed).doesNotContainKey("BANK");
        assertThat(listed.get("TERMINAL")).isEqualByComparingTo("770000");
        assertThat(listed.get("CARD")).isEqualByComparingTo("630000");
    }

    @Test
    void transactions_filteredByChannel_splitRowInBoth_withChannelAmounts() throws Exception {
        Long reg = scenario()[0];
        fixtures.loginAs(UserRole.SUPER_ADMIN);

        List<CashTransactionDto> online = cash.getTransactions(reg, null, null, null, null, null, null, "ONLINE",
            PageRequest.of(0, 50)).getContent();
        assertThat(online).extracting(CashTransactionDto::getPaymentMethod).containsExactly(PaymentMethod.CLICK);

        List<CashTransactionDto> card = cash.getTransactions(reg, null, null, null, null, null, null, "CARD",
            PageRequest.of(0, 50)).getContent();
        assertThat(card).extracting(CashTransactionDto::getPaymentMethod)
            .containsExactlyInAnyOrder(PaymentMethod.CARD, PaymentMethod.CASH_AND_CARD, PaymentMethod.CARD);
        CashTransactionDto split = card.stream()
            .filter(t -> t.getPaymentMethod() == PaymentMethod.CASH_AND_CARD).findFirst().orElseThrow();
        assertThat(split.getChannelAmounts()).containsOnlyKeys("CASH", "CARD");
        assertThat(split.getChannelAmounts().get("CASH")).isEqualByComparingTo("250000");
        assertThat(split.getChannelAmounts().get("CARD")).isEqualByComparingTo("450000");

        List<CashTransactionDto> cashRows = cash.getTransactions(reg, null, null, null, null, null, null, "CASH",
            PageRequest.of(0, 50)).getContent();
        // CASH kirim, CASH_AND_CARD, CASH chiqim, to'lov kirimi va uning REVERSAL i
        assertThat(cashRows).hasSize(5);

        assertThatThrownBy(() -> cash.getTransactions(reg, null, null, null, null, null, null, "CRYPTO",
            PageRequest.of(0, 50))).isInstanceOf(BadRequestException.class);

        mvc.perform(get("/api/cash-registers/{id}/transactions", reg).param("channel", "TERMINAL")
                .with(user("acc").roles("ACCOUNTANT")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(3))
            .andExpect(jsonPath("$.data.content[0].channelAmounts.TERMINAL").exists());
    }

    private static void assertBankRejected(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOf(CodedException.class)
            .extracting(e -> ((CodedException) e).getCode()).isEqualTo("payment.method.bankNotAccepted");
    }

    /** Buyurtmachi qarori 2026-10-05: BANK yangi yozuvda — 400 (uz/ru/en), channel=BANK — 400. */
    @Test
    void bank_rejectedForNewRecords_andAsChannel() throws Exception {
        Long reg = fixtures.cashRegister(true);
        Long other = fixtures.cashRegister(false);
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        assertBankRejected(() -> income(reg, PaymentMethod.BANK, 100_000, null, null));
        income(reg, PaymentMethod.CASH, 100_000, null, null);
        assertBankRejected(() -> expense(reg, PaymentMethod.BANK, 10_000));
        TransferDto t = new TransferDto();
        t.setFromCashRegisterId(reg);
        t.setToCashRegisterId(other);
        t.setAmount(BigDecimal.valueOf(10_000));
        t.setPaymentMethod(PaymentMethod.BANK);
        assertBankRejected(() -> cash.transfer(t));

        Long student = fixtures.student();
        Long group = fixtures.group(fixtures.course(700_000));
        Long sg = fixtures.enrollment(student, group).start(DAY).save();
        accrual.accrueUpTo(sg, DAY);
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        PaymentRequest r = new PaymentRequest();
        r.setStudentId(student);
        r.setGroupId(group);
        r.setAmount(BigDecimal.valueOf(700_000));
        r.setCashRegisterId(reg);
        r.setPaymentMethod(PaymentMethod.BANK);
        assertBankRejected(() -> payments.createPayment(r, null));
        r.setPaymentMethod(PaymentMethod.CASH);
        r.setPaymentMethodForCash("BANK_TRANSFER");                 // eski nom → BANK → 400
        assertBankRejected(() -> payments.createPayment(r, null));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payments", Long.class)).isZero();

        assertThatThrownBy(() -> cash.getTransactions(reg, null, null, null, null, null, null, "BANK",
            PageRequest.of(0, 50))).isInstanceOf(BadRequestException.class);
        mvc.perform(get("/api/cash-registers/{id}/transactions", reg).param("channel", "BANK")
                .with(user("acc").roles("ACCOUNTANT")))
            .andExpect(status().isBadRequest());

        String body = "{\"amount\":1000,\"paymentMethod\":\"BANK\",\"transactionDate\":\"2026-09-15\","
            + "\"transactionType\":\"Boshqa kirim\"}";
        for (String[] lang : new String[][]{{"uz", "TERMINAL ni tanlang"}, {"ru", "Способ оплаты BANK"},
                {"en", "Payment method BANK is no longer accepted"}}) {
            mvc.perform(post("/api/cash-registers/{id}/income", reg).header("Accept-Language", lang[0])
                    .with(user("test-super_admin").roles("SUPER_ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("payment.method.bankNotAccepted"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(lang[1])));
        }
    }

    @Test
    void byMethodReport_periodFlows_andFinanceReportSplit() throws Exception {
        Long reg = scenario()[0];

        CashChannelReportDto day = cash.getChannelReport(reg, DAY, DAY);
        assertThat(channel(day.getChannels(), "TERMINAL").getIncome()).isEqualByComparingTo("800000");
        assertThat(channel(day.getChannels(), "TERMINAL").getExpense()).isEqualByComparingTo("30000");
        assertThat(day.getTotal().getChannel()).isNull();

        mvc.perform(get("/api/cash-registers/{id}/by-method", reg)
                .with(user("acc").roles("ACCOUNTANT")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.channels.length()").value(5))
            .andExpect(jsonPath("$.data.channels[3].channel").value("ONLINE"))
            .andExpect(jsonPath("$.data.channels[3].net").value(400000.0));

        // Bekor qilish va o'tkazma haqiqiy "bugun" sanasi bilan yoziladi — keng oraliq
        FinanceReportResponse r = finance.getFinanceReport(LocalDate.of(2000, 1, 1), LocalDate.of(2100, 12, 31));
        assertThat(r.getIncomeByMethod()).containsOnlyKeys("CASH", "CARD", "TERMINAL", "ONLINE", "OTHER");
        assertThat(r.getIncomeByMethod().get("TERMINAL")).isEqualByComparingTo("800000");
        assertThat(r.getIncomeByMethod().get("CASH")).isEqualByComparingTo("350000");      // 100+250+630−630
        assertThat(r.getIncomeByMethod().get("CARD")).isEqualByComparingTo("650000");      // 200+450
        assertThat(r.getIncomeByMethod().get("ONLINE")).isEqualByComparingTo("400000");
        assertThat(r.getExpenseByMethod().get("CASH")).isEqualByComparingTo("50000");
        assertThat(r.getExpenseByMethod().get("TERMINAL")).isEqualByComparingTo("30000");
        assertThat(r.getExpenseByMethod().get("CARD")).isEqualByComparingTo("0");          // o'tkazma chiqim emas
        // Ikki kassa birga: o'tkazma ichki — jami 0
        assertThat(channel(r.getCashFlowByMethod(), "CARD").getTransferIn()).isEqualByComparingTo("20000");
        assertThat(channel(r.getCashFlowByMethod(), "CARD").getTransferOut()).isEqualByComparingTo("20000");
    }
}
