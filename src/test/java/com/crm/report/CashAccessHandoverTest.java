package com.crm.report;

import com.crm.billing.AccrualService;
import com.crm.billing.support.AbstractBillingIT;
import com.crm.dto.request.CashHandoverRequests;
import com.crm.dto.request.ExpenseCreateDto;
import com.crm.dto.request.ExpenseRequest;
import com.crm.dto.request.IncomeCreateDto;
import com.crm.dto.request.PaymentRequest;
import com.crm.dto.response.CashHandoverDto;
import com.crm.dto.response.CashHandoverPreviewDto;
import com.crm.dto.response.FinanceReportResponse;
import com.crm.entity.CashRegister;
import com.crm.entity.enums.CashHandoverStatus;
import com.crm.entity.enums.PaymentMethod;
import com.crm.entity.enums.UserRole;
import com.crm.exception.CodedException;
import com.crm.exception.ConflictException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.CashRegisterRepository;
import com.crm.repository.CashTransactionRepository;
import com.crm.service.CashHandoverService;
import com.crm.service.CashRegisterService;
import com.crm.service.FinanceService;
import com.crm.service.PaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Kassa va ruxsatlar — 1-bosqich (docs/design/cash-and-books.md, V86): kirish matritsasi, xarajat guruhlari,
 * kunlik topshirish (qabul, kamomad, ortiqcha, razmen, rad etish) va moliya hisoboti to'liqligi.
 */
class CashAccessHandoverTest extends AbstractBillingIT {

    private static final LocalDate WIDE_FROM = LocalDate.of(2000, 1, 1);
    private static final LocalDate WIDE_TO = LocalDate.of(2100, 12, 31);

    @Autowired CashRegisterService cash;
    @Autowired CashHandoverService handovers;
    @Autowired FinanceService finance;
    @Autowired PaymentService payments;
    @Autowired AccrualService accrual;
    @Autowired CashRegisterRepository registerRepo;
    @Autowired CashTransactionRepository txRepo;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    private Long reg1;
    private Long reg2;
    private Long dir;
    private Long adm1;
    private Long adm2;

    @BeforeEach
    void kassalar() {
        reg1 = fixtures.bareRegister(true);
        reg2 = fixtures.bareRegister(false);
        dir = fixtures.directorRegister();
        adm1 = fixtures.user(UserRole.ADMIN, "kassa-adm1");
        adm2 = fixtures.user(UserRole.ACCOUNTANT, "kassa-acc2");
        fixtures.member(reg1, adm1);
        fixtures.member(reg2, adm2);
        fixtures.user(UserRole.ADMIN, "kassa-adm3");      // hech qaysi kassaga biriktirilmagan
    }

    private static RequestPostProcessor as(String username, String role) {
        return user(username).roles(role);
    }

    private static RequestPostProcessor sa() {
        return user("test-super_admin").roles("SUPER_ADMIN");
    }

    private void income(Long reg, PaymentMethod m, long amount) {
        IncomeCreateDto dto = new IncomeCreateDto();
        dto.setAmount(BigDecimal.valueOf(amount));
        dto.setPaymentMethod(m);
        dto.setTransactionType("Boshqa kirim");
        cash.addIncome(reg, dto);
    }

    private void expense(Long reg, String category, PaymentMethod m, long amount) {
        ExpenseCreateDto dto = new ExpenseCreateDto();
        dto.setAmount(BigDecimal.valueOf(amount));
        dto.setPaymentMethod(m);
        dto.setCategory(category);
        cash.addExpense(reg, dto);
    }

    private CashRegister register(Long id) {
        return inTx(() -> registerRepo.findById(id).orElseThrow());
    }

    private void setFloat(Long reg, long amount) {
        jdbc.update("UPDATE cash_registers SET float_amount = ? WHERE id = ?", BigDecimal.valueOf(amount), reg);
    }

    private CashHandoverDto submit(Long reg, long declared, String hash) {
        CashHandoverRequests.Submit s = new CashHandoverRequests.Submit();
        s.setDeclaredCash(BigDecimal.valueOf(declared));
        s.setPreviewHash(hash);
        return handovers.submit(reg, s, null);
    }

    private CashHandoverDto accept(Long id, long counted, Map<String, BigDecimal> actuals) {
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        CashHandoverRequests.Accept a = new CashHandoverRequests.Accept();
        a.setCountedCash(BigDecimal.valueOf(counted));
        a.setActuals(actuals);
        a.setDiffNote("sanaldi");
        return handovers.accept(id, a);
    }

    private static CashHandoverDto.Line line(List<CashHandoverDto.Line> lines, PaymentMethod m) {
        return lines.stream().filter(l -> l.getPaymentMethod() == m).findFirst().orElseThrow();
    }

    // ── 1. Ruxsatlar matritsasi ─────────────────────────────────────────

    @Test
    void registers_adminSeesOnlyOwn_otherAndDirector404_unassigned400_saSeesAll() throws Exception {
        mvc.perform(get("/api/cash-registers").with(as("kassa-adm1", "ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].id").value(reg1))
            .andExpect(jsonPath("$.data[0].kind").value("ADMIN"));
        mvc.perform(get("/api/cash-registers/" + reg1).with(as("kassa-adm1", "ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/api/cash-registers/" + reg2).with(as("kassa-adm1", "ADMIN"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/cash-registers/" + dir).with(as("kassa-adm1", "ADMIN"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/cash-registers/" + dir + "/transactions").with(as("kassa-adm1", "ADMIN")))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/cash-registers/" + reg1 + "/balance").with(as("kassa-acc2", "ACCOUNTANT")))
            .andExpect(status().isNotFound());
        // ACCOUNTANT = admin kabi: o'z kassasi
        mvc.perform(get("/api/cash-registers/my").with(as("kassa-acc2", "ACCOUNTANT")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].id").value(reg2));
        // Biriktirilmagan admin — tushunarli 400
        mvc.perform(get("/api/cash-registers/my").with(as("kassa-adm3", "ADMIN")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("cash.noRegisterAssigned"));
        mvc.perform(get("/api/cash-registers/" + reg1).with(as("kassa-adm3", "ADMIN")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("cash.noRegisterAssigned"));
        // SA — hammasi, Direktor kassasi ham
        mvc.perform(get("/api/cash-registers").with(sa()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(3));
    }

    @Test
    void structureTransferReportsPayrollRefund_saOnly() throws Exception {
        String body = "{\"name\":\"X\"}";
        mvc.perform(post("/api/cash-registers").with(as("kassa-adm1", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/cash-registers/" + reg1).with(as("kassa-adm1", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/cash-registers/" + reg1 + "/members").with(as("kassa-adm1", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"userIds\":[]}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/cash-registers/transfer").with(as("kassa-adm1", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromCashRegisterId\":" + reg1 + ",\"toCashRegisterId\":" + dir
                    + ",\"amount\":1000,\"paymentMethod\":\"CASH\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/finance/report").with(as("kassa-adm1", "ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/finance/report").with(as("kassa-acc2", "ACCOUNTANT"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/finance/report").with(sa())).andExpect(status().isOk());
        mvc.perform(post("/api/payroll/1/pay").with(as("kassa-adm1", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/students/1/refund-payout").with(as("kassa-adm1", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1000,\"cashRegisterId\":1,\"reason\":\"test\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/cash-handovers/1/accept").with(as("kassa-adm1", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"countedCash\":0}"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/cash-handovers/report").with(as("kassa-adm1", "ADMIN"))).andExpect(status().isForbidden());
        // Direktor dashboardi: moliya bo'limlari — faqat SA (ACCOUNTANT endi admin kabi)
        mvc.perform(get("/api/dashboard/director/collections/periods").with(as("kassa-acc2", "ACCOUNTANT")))
            .andExpect(status().isForbidden());
    }

    @Test
    void paymentsAndIncome_onlyIntoMemberRegister() {
        Long student = fixtures.student();
        Long group = fixtures.group(fixtures.course(700_000));
        Long sg = fixtures.enrollment(student, group).start(LocalDate.of(2026, 9, 15)).save();
        accrual.accrueUpTo(sg, LocalDate.of(2026, 9, 15));

        fixtures.loginAs("kassa-adm1", UserRole.ADMIN);
        PaymentRequest r = new PaymentRequest();
        r.setStudentId(student);
        r.setGroupId(group);
        r.setAmount(BigDecimal.valueOf(300_000));
        r.setPaymentMethod(PaymentMethod.CASH);
        r.setCashRegisterId(reg2);
        assertThatThrownBy(() -> payments.createPayment(r, null)).isInstanceOf(ResourceNotFoundException.class);
        r.setCashRegisterId(dir);
        assertThatThrownBy(() -> payments.createPayment(r, null)).isInstanceOf(ResourceNotFoundException.class);
        r.setCashRegisterId(reg1);
        payments.createPayment(r, null);
        assertThat(register(reg1).getCashBalance()).isEqualByComparingTo("300000");
        assertThat(inTx(() -> txRepo.findAll()).stream().filter(t -> t.getPaymentId() != null)
            .map(t -> t.getCategory()).toList()).containsExactly("STUDENT_PAYMENT");

        assertThatThrownBy(() -> income(reg2, PaymentMethod.CASH, 1_000)).isInstanceOf(ResourceNotFoundException.class);
        fixtures.loginAs("kassa-adm3", UserRole.ADMIN);
        assertThatThrownBy(() -> income(reg1, PaymentMethod.CASH, 1_000))
            .isInstanceOfSatisfying(CodedException.class,
                e -> assertThat(e.getCode()).isEqualTo("cash.noRegisterAssigned"));
    }

    // ── 2. Xarajat guruhlari ────────────────────────────────────────────

    @Test
    void expenses_dailyByAdminOwnRegister_strategicSaDirectorOnly_systemRejected() {
        fixtures.loginAs("kassa-adm1", UserRole.ADMIN);
        income(reg1, PaymentMethod.CASH, 100_000);
        expense(reg1, "DELIVERY", PaymentMethod.CASH, 20_000);
        expense(reg1, null, PaymentMethod.CASH, 5_000);          // kategoriyasiz — OTHER_DAILY
        assertThatThrownBy(() -> expense(reg1, "RENT", PaymentMethod.CASH, 1_000))
            .isInstanceOfSatisfying(CodedException.class,
                e -> assertThat(e.getCode()).isEqualTo("cash.category.directorOnly"));
        assertThatThrownBy(() -> expense(reg1, "SALARY", PaymentMethod.CASH, 1_000))
            .isInstanceOfSatisfying(CodedException.class,
                e -> assertThat(e.getCode()).isEqualTo("cash.category.system"));
        assertThatThrownBy(() -> expense(reg2, "DELIVERY", PaymentMethod.CASH, 1_000))
            .isInstanceOf(ResourceNotFoundException.class);

        ExpenseRequest noRegister = new ExpenseRequest();
        noRegister.setCategory("STATIONERY");
        noRegister.setTitle("Qog'oz");
        noRegister.setAmount(BigDecimal.valueOf(3_000));
        noRegister.setExpenseDate(LocalDate.of(2026, 9, 15));
        assertThatThrownBy(() -> finance.createExpense(noRegister))
            .isInstanceOfSatisfying(CodedException.class,
                e -> assertThat(e.getCode()).isEqualTo("expense.cashRegister.required"));
        noRegister.setCashRegisterId(reg1);
        assertThat(finance.createExpense(noRegister).getCategory()).isEqualTo("STATIONERY");
        // Admin xarajatlar ro'yxati: faqat o'z kassasidagi DAILY
        assertThat(finance.getExpensesFiltered(null, null, null, 0, 50).getContent())
            .extracting(e -> e.getCategory()).containsExactly("STATIONERY");

        fixtures.loginAs(UserRole.SUPER_ADMIN);
        assertThatThrownBy(() -> expense(reg1, "RENT", PaymentMethod.CASH, 1_000))
            .isInstanceOfSatisfying(CodedException.class,
                e -> assertThat(e.getCode()).isEqualTo("cash.directorRegisterRequired"));
        expense(dir, "RENT", PaymentMethod.CASH, 400_000);
        ExpenseRequest bank = new ExpenseRequest();
        bank.setCategory("MARKETING");
        bank.setTitle("Target");
        bank.setAmount(BigDecimal.valueOf(250_000));
        bank.setExpenseDate(LocalDate.of(2026, 9, 15));
        finance.createExpense(bank);                              // kassasiz (bank) — faqat SA
        expense(reg1, "HOUSEHOLD", PaymentMethod.CASH, 7_000);    // SA kundalikni istalgan kassadan

        assertThat(inTx(() -> txRepo.findAll()).stream()
            .filter(t -> t.getCashRegister().getId().equals(reg1) && t.getType().name().equals("EXPENSE"))
            .map(t -> t.getCategory()).toList())
            .containsExactlyInAnyOrder("DELIVERY", "OTHER_DAILY", "STATIONERY", "HOUSEHOLD");
    }

    // ── 3. Kunlik topshirish ────────────────────────────────────────────

    @Test
    void handover_fullFlow_floatStays_cashToDirector_nonCashComparedOnly_newEntriesNextHandover() {
        setFloat(reg1, 50_000);
        fixtures.loginAs("kassa-adm1", UserRole.ADMIN);
        income(reg1, PaymentMethod.CASH, 300_000);
        income(reg1, PaymentMethod.CARD, 200_000);
        income(reg1, PaymentMethod.CLICK, 100_000);
        expense(reg1, "DELIVERY", PaymentMethod.CASH, 20_000);

        CashHandoverPreviewDto p = handovers.preview(reg1);
        assertThat(p.getTransactionCount()).isEqualTo(4);
        assertThat(p.getExpectedCash()).isEqualByComparingTo("230000");        // 280 000 − razmen 50 000
        assertThat(line(p.getLines(), PaymentMethod.CASH).getExpectedNet()).isEqualByComparingTo("280000");
        assertThat(line(p.getLines(), PaymentMethod.CASH).getExpectedOut()).isEqualByComparingTo("20000");
        assertThat(line(p.getLines(), PaymentMethod.CARD).getExpectedNet()).isEqualByComparingTo("200000");
        assertThat(line(p.getLines(), PaymentMethod.CLICK).getExpectedNet()).isEqualByComparingTo("100000");
        assertThat(p.getByCategory()).extracting(c -> c.getCategory()).containsExactly("OTHER_INCOME", "DELIVERY");

        CashHandoverDto h = submit(reg1, 230_000, p.getPreviewHash());
        assertThat(h.getStatus()).isEqualTo(CashHandoverStatus.SUBMITTED);
        assertThatThrownBy(() -> submit(reg1, 0, null)).isInstanceOf(ConflictException.class);

        // Topshirishdan keyingi to'lov — keyingi topshirishga
        income(reg1, PaymentMethod.CASH, 10_000);
        assertThat(handovers.preview(reg1).getTransactionCount()).isEqualTo(1);
        // Admin qabul qila olmaydi
        assertThatThrownBy(() -> handovers.accept(h.getId(), new CashHandoverRequests.Accept()))
            .isInstanceOf(CodedException.class);

        CashHandoverDto done = accept(h.getId(), 230_000,
            Map.of("CARD", BigDecimal.valueOf(200_000), "CLICK", BigDecimal.valueOf(90_000)));
        assertThat(done.getStatus()).isEqualTo(CashHandoverStatus.ACCEPTED);
        assertThat(done.getCashDiff()).isEqualByComparingTo("0");
        assertThat(done.getTargetCashRegisterId()).isEqualTo(dir);
        assertThat(line(done.getLines(), PaymentMethod.CLICK).getDiff()).isEqualByComparingTo("-10000");
        assertThat(line(done.getLines(), PaymentMethod.CARD).getDiff()).isEqualByComparingTo("0");

        // Naqd Direktor kassasiga o'tdi, razmen + keyingi kirim qoldi; naqdsiz ko'chirilmadi
        assertThat(register(dir).getCashBalance()).isEqualByComparingTo("230000");
        assertThat(register(dir).getPlasticBalance()).isEqualByComparingTo("0");
        assertThat(register(reg1).getCashBalance()).isEqualByComparingTo("60000");
        assertThat(register(reg1).getPlasticBalance()).isEqualByComparingTo("300000");

        // Keyingi preview: faqat 10 000, topshirish o'tkazmalari kirmaydi
        fixtures.loginAs("kassa-adm1", UserRole.ADMIN);
        CashHandoverPreviewDto next = handovers.preview(reg1);
        assertThat(next.getTransactionCount()).isEqualTo(1);
        assertThat(next.getExpectedCash()).isEqualByComparingTo("10000");
    }

    @Test
    void handover_shortageRecorded_surplusRecorded_noPenalty_reportCountsShortage() {
        fixtures.loginAs("kassa-acc2", UserRole.ACCOUNTANT);
        income(reg2, PaymentMethod.CASH, 100_000);
        CashHandoverDto h = submit(reg2, 100_000, null);
        CashHandoverDto done = accept(h.getId(), 90_000, null);
        assertThat(done.getCashDiff()).isEqualByComparingTo("-10000");
        assertThat(register(reg2).getCashBalance()).isEqualByComparingTo("0");
        assertThat(register(dir).getCashBalance()).isEqualByComparingTo("90000");
        assertThat(inTx(() -> txRepo.findAll()).stream()
            .filter(t -> "CASH_SHORTAGE".equals(t.getCategory())).map(t -> t.getAmount()).toList())
            .singleElement().satisfies(a -> assertThat(a).isEqualByComparingTo("10000"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bonus_penalties", Long.class)).isZero();

        fixtures.loginAs("kassa-acc2", UserRole.ACCOUNTANT);
        income(reg2, PaymentMethod.CASH, 50_000);
        CashHandoverDto h2 = submit(reg2, 50_000, null);
        CashHandoverDto done2 = accept(h2.getId(), 55_000, null);
        assertThat(done2.getCashDiff()).isEqualByComparingTo("5000");
        assertThat(register(reg2).getCashBalance()).isEqualByComparingTo("0");

        FinanceReportResponse r = finance.getFinanceReport(WIDE_FROM, WIDE_TO);
        assertThat(r.getCashShortage()).isEqualByComparingTo("10000");
        assertThat(r.getExpenseByCategory().get("CASH_SHORTAGE")).isEqualByComparingTo("10000");
        assertThat(r.getIncomeByCategory().get("CASH_SURPLUS")).isEqualByComparingTo("5000");
        assertThat(r.getOtherIncome()).isEqualByComparingTo("155000");        // 150 000 + ortiqcha 5 000
        assertThat(handovers.report(WIDE_FROM, WIDE_TO)).singleElement().satisfies(row -> {
            assertThat((BigDecimal) row.get("shortage")).isEqualByComparingTo("10000");
            assertThat((BigDecimal) row.get("surplus")).isEqualByComparingTo("5000");
            assertThat((Long) row.get("count")).isEqualTo(2L);
        });
    }

    @Test
    void handover_reject_releasesEntries_resubmitWorks_otherAdmin404() throws Exception {
        fixtures.loginAs("kassa-adm1", UserRole.ADMIN);
        income(reg1, PaymentMethod.CASH, 40_000);
        CashHandoverDto h = submit(reg1, 40_000, null);
        assertThat(handovers.preview(reg1).getTransactionCount()).isZero();
        assertThat(handovers.preview(reg1).getPendingHandoverId()).isEqualTo(h.getId());

        fixtures.loginAs(UserRole.SUPER_ADMIN);
        CashHandoverRequests.Reject rej = new CashHandoverRequests.Reject();
        rej.setReason("Qayta sanang");
        assertThat(handovers.reject(h.getId(), rej).getStatus()).isEqualTo(CashHandoverStatus.REJECTED);

        fixtures.loginAs("kassa-adm1", UserRole.ADMIN);
        assertThat(handovers.preview(reg1).getTransactionCount()).isEqualTo(1);
        assertThat(submit(reg1, 40_000, null).getStatus()).isEqualTo(CashHandoverStatus.SUBMITTED);

        mvc.perform(get("/api/cash-registers/" + reg1 + "/handover/preview").with(as("kassa-acc2", "ACCOUNTANT")))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/cash-handovers/" + h.getId()).with(as("kassa-acc2", "ACCOUNTANT")))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/cash-handovers").with(as("kassa-acc2", "ACCOUNTANT")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(get("/api/cash-handovers").with(as("kassa-adm1", "ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(2));
        // Previewdan keyin yangi yozuv — 409
        income(reg1, PaymentMethod.CASH, 1_000);
        String stale = handovers.preview(reg1).getPreviewHash();
        income(reg1, PaymentMethod.CASH, 1_000);
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        CashHandoverRequests.Reject rej2 = new CashHandoverRequests.Reject();
        rej2.setReason("Yana");
        handovers.list("SUBMITTED", reg1, null, null, 0, 10).forEach(x -> handovers.reject(x.getId(), rej2));
        fixtures.loginAs("kassa-adm1", UserRole.ADMIN);
        assertThatThrownBy(() -> submit(reg1, 0, stale))
            .isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.getCode()).isEqualTo("cash.handover.previewChanged"));
    }

    @Test
    void members_saAssigns_oneRegisterPerAdmin_rolesChecked() throws Exception {
        mvc.perform(put("/api/cash-registers/" + reg2 + "/members").with(sa())
                .contentType(MediaType.APPLICATION_JSON).content("{\"userIds\":[" + adm1 + "]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.members[0].userId").value(adm1));
        // adm1 reg1 dan ko'chirildi
        mvc.perform(get("/api/cash-registers/my").with(as("kassa-adm1", "ADMIN")))
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].id").value(reg2));
        Long teacher = fixtures.user(UserRole.TEACHER, "kassa-teacher");
        mvc.perform(put("/api/cash-registers/" + reg1 + "/members").with(sa())
                .contentType(MediaType.APPLICATION_JSON).content("{\"userIds\":[" + teacher + "]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("cash.members.invalidUser"));
        mvc.perform(put("/api/cash-registers/" + dir + "/members").with(sa())
                .contentType(MediaType.APPLICATION_JSON).content("{\"userIds\":[" + adm2 + "]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("cash.members.directorRegister"));
        // Razmen va tur — SA
        mvc.perform(put("/api/cash-registers/" + reg1).with(sa())
                .contentType(MediaType.APPLICATION_JSON).content("{\"floatAmount\":75000}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.floatAmount").value(75000));
    }

    @Test
    void categories_adminSeesDailyOnly_saManages() throws Exception {
        String adminList = mvc.perform(get("/api/cash-categories").with(as("kassa-adm1", "ADMIN")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> rows = json.readValue(json.readTree(adminList).get("data").toString(),
            new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() { });
        assertThat(rows).isNotEmpty();
        assertThat(rows.stream().map(r -> String.valueOf(r.get("scope"))).distinct().toList()).containsExactly("DAILY");
        mvc.perform(post("/api/cash-categories").with(as("kassa-adm1", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/cash-categories").with(sa()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"LEGAL\",\"direction\":\"OUT\",\"scope\":\"STRATEGIC\",\"name\":\"Yurist\"}"))
            .andExpect(status().isCreated());
        mvc.perform(post("/api/cash-categories").with(sa()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"X1\",\"direction\":\"OUT\",\"scope\":\"SYSTEM\",\"name\":\"x\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("cash.category.scopeInvalid"));
        mvc.perform(put("/api/cash-categories/SALARY").with(sa()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}"))
            .andExpect(status().isBadRequest());
    }

    /**
     * Billing v2 dan oldingi to'lov kirimi: payment_id yo'q, nomi "O'quvchi to'lovi" — STUDENT_PAYMENT (to'lov
     * payments da, totalIncome ga kiradi — boshqa kirim EMAS). Nomsiz kirim — OTHER_INCOME.
     */
    @Test
    void legacyStudentPaymentIncome_isStudentPayment_notOtherIncome_namelessIsOther() {
        fixtures.loginAs("kassa-adm1", UserRole.ADMIN);
        IncomeCreateDto legacy = new IncomeCreateDto();
        legacy.setAmount(BigDecimal.valueOf(500_000));
        legacy.setPaymentMethod(PaymentMethod.CLICK);
        legacy.setTransactionType("O'quvchi to'lovi");
        cash.addIncome(reg1, legacy);
        IncomeCreateDto nameless = new IncomeCreateDto();
        nameless.setAmount(BigDecimal.valueOf(70_000));
        nameless.setPaymentMethod(PaymentMethod.CASH);
        cash.addIncome(reg1, nameless);
        // Eski yozuvga aylantiramiz: kategoriyasiz, payment_id siz
        jdbc.update("UPDATE cash_transactions SET category = NULL, payment_id = NULL WHERE cash_register_id = ?", reg1);

        fixtures.loginAs(UserRole.SUPER_ADMIN);
        FinanceReportResponse r = finance.getFinanceReport(WIDE_FROM, WIDE_TO);
        assertThat(r.getOtherIncome()).isEqualByComparingTo("70000");
        assertThat(r.getIncomeByCategory()).doesNotContainKey("STUDENT_PAYMENT");
        assertThat(r.getIncomeByCategory().get("OTHER_INCOME")).isEqualByComparingTo("70000");

        // Kassa ko'rinishi va topshirish preview'i — bir xil qoida (CashCategoryTotals)
        assertThat(cash.getCategoryReport(reg1, null, null))
            .extracting(c -> c.getCategory() + "=" + c.getAmount().stripTrailingZeros().toPlainString())
            .containsExactly("STUDENT_PAYMENT=500000", "OTHER_INCOME=70000");
        fixtures.loginAs("kassa-adm1", UserRole.ADMIN);
        assertThat(handovers.preview(reg1).getByCategory())
            .extracting(c -> c.getCategory() + "=" + c.getAmount().stripTrailingZeros().toPlainString())
            .containsExactly("STUDENT_PAYMENT=500000", "OTHER_INCOME=70000");
    }

    // ── 4. Moliya hisoboti to'liqligi ───────────────────────────────────

    @Test
    void financeReport_includesOtherIncome_directExpenses_noRegisterExpenses_legacyRows_byScope() {
        fixtures.loginAs("kassa-adm1", UserRole.ADMIN);
        income(reg1, PaymentMethod.CASH, 100_000);                 // boshqa kirim
        expense(reg1, "DELIVERY", PaymentMethod.CASH, 20_000);     // kassadan to'g'ridan (DAILY)
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        ExpenseRequest bank = new ExpenseRequest();
        bank.setCategory("RENT");
        bank.setTitle("Ijara");
        bank.setAmount(BigDecimal.valueOf(50_000));
        bank.setExpenseDate(LocalDate.of(2026, 9, 15));
        finance.createExpense(bank);                                // kassasiz STRATEGIC
        expense(dir, "MARKETING", PaymentMethod.CARD, 70_000);     // Direktor kassasidan STRATEGIC
        // Eski yozuvlar (backfill'dan oldin): kategoriyasiz "Xarajat: UTILITIES" va nomsiz chiqim, kategoriyasiz kirim
        expense(dir, "OTHER", PaymentMethod.CASH, 30_000);
        expense(dir, "OTHER", PaymentMethod.CASH, 11_000);
        income(dir, PaymentMethod.CASH, 9_000);
        jdbc.update("UPDATE cash_transactions SET category = NULL, transaction_name = 'Xarajat: UTILITIES' "
            + "WHERE type = 'EXPENSE' AND amount = 30000");
        jdbc.update("UPDATE cash_transactions SET category = NULL, transaction_name = NULL "
            + "WHERE type = 'EXPENSE' AND amount = 11000");
        jdbc.update("UPDATE cash_transactions SET category = NULL WHERE type = 'INCOME' AND amount = 9000");

        FinanceReportResponse r = finance.getFinanceReport(WIDE_FROM, WIDE_TO);
        assertThat(r.getOtherIncome()).isEqualByComparingTo("109000");
        assertThat(r.getIncomeByCategory().get("OTHER_INCOME")).isEqualByComparingTo("109000");
        assertThat(r.getExpenseByCategory()).containsOnlyKeys("DELIVERY", "RENT", "MARKETING", "UTILITIES", "OTHER");
        assertThat(r.getExpenseByCategory().get("UTILITIES")).isEqualByComparingTo("30000");
        assertThat(r.getExpenseByCategory().get("OTHER")).isEqualByComparingTo("11000");
        assertThat(r.getTotalExpenses()).isEqualByComparingTo("181000");
        assertThat(r.getExpenseByScope().get("DAILY")).isEqualByComparingTo("20000");
        assertThat(r.getExpenseByScope().get("STRATEGIC")).isEqualByComparingTo("161000");
        assertThat(r.getNetProfit()).isEqualByComparingTo(r.getTotalIncome().add(r.getExamFees())
            .add(r.getOtherIncome()).subtract(r.getTotalExpenses()).subtract(r.getPayrollPaid())
            .subtract(r.getRefunds()));
        assertThat(r.getNetProfit()).isEqualByComparingTo("-72000");

        // Kassa ko'rinishi: kategoriya bo'yicha
        assertThat(cash.getCategoryReport(reg1, null, null)).extracting(c -> c.getCategory())
            .containsExactly("OTHER_INCOME", "DELIVERY");
    }
}
