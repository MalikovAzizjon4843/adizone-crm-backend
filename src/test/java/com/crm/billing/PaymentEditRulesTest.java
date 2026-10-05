package com.crm.billing;

import com.crm.billing.support.AbstractBillingIT;
import com.crm.dto.request.ExpenseCreateDto;
import com.crm.dto.request.ExpenseRequest;
import com.crm.dto.request.IncomeCreateDto;
import com.crm.dto.request.PaymentRequest;
import com.crm.entity.enums.ExpenseCategory;
import com.crm.entity.enums.PaymentMethod;
import com.crm.entity.enums.UserRole;
import com.crm.exception.CodedException;
import com.crm.service.CashRegisterService;
import com.crm.service.FinanceService;
import com.crm.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Buyurtmachi qarori 2026-10-05: pul yozuvi sanasi (kelajak — 400, o'tgan — faqat SA) va to'lovni
 * tahrirlash / bekor qilish faqat SUPER_ADMIN (SecurityConfig + servis), har bekor qilish audit'da.
 */
class PaymentEditRulesTest extends AbstractBillingIT {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 15);   // AbstractBillingIT soati

    @Autowired PaymentService payments;
    @Autowired AccrualService accrual;
    @Autowired CashRegisterService cash;
    @Autowired FinanceService finance;
    @Autowired JdbcTemplate jdbc;

    private record Ids(Long student, Long group, Long sg) {
    }

    private Ids enrollment() {
        Long student = fixtures.student();
        Long group = fixtures.group(fixtures.course(700_000));
        Long sg = fixtures.enrollment(student, group).start(TODAY).save();
        accrual.accrueUpTo(sg, TODAY);
        return new Ids(student, group, sg);
    }

    private static PaymentRequest req(Ids ids, Long register, LocalDate date) {
        PaymentRequest r = new PaymentRequest();
        r.setStudentId(ids.student());
        r.setGroupId(ids.group());
        r.setAmount(BigDecimal.valueOf(700_000));
        r.setCashRegisterId(register);
        r.setPaymentMethod(PaymentMethod.CASH);
        r.setPaymentDate(date);
        return r;
    }

    private static IncomeCreateDto income(LocalDate date) {
        IncomeCreateDto dto = new IncomeCreateDto();
        dto.setAmount(BigDecimal.valueOf(10_000));
        dto.setPaymentMethod(PaymentMethod.CASH);
        dto.setTransactionDate(date);
        dto.setTransactionType("Boshqa kirim");
        return dto;
    }

    private static ExpenseCreateDto cashExpense(LocalDate date) {
        ExpenseCreateDto dto = new ExpenseCreateDto();
        dto.setAmount(BigDecimal.valueOf(1_000));
        dto.setPaymentMethod(PaymentMethod.CASH);
        dto.setTransactionDate(date);
        return dto;
    }

    private static ExpenseRequest expense(LocalDate date) {
        ExpenseRequest e = new ExpenseRequest();
        e.setCategory(ExpenseCategory.values()[0]);
        e.setTitle("Qog'oz");
        e.setAmount(BigDecimal.valueOf(5_000));
        e.setExpenseDate(date);
        return e;
    }

    private static void assertCode(Runnable call, String code, HttpStatus status) {
        assertThatThrownBy(call::run).isInstanceOf(CodedException.class)
            .satisfies(e -> {
                assertThat(((CodedException) e).getCode()).isEqualTo(code);
                assertThat(((CodedException) e).getStatus()).isEqualTo(status);
            });
    }

    private static RequestPostProcessor as(String role) {
        return user("mvc-" + role.toLowerCase()).roles(role);
    }

    // ── TASK 1: sana ────────────────────────────────────────────────────

    @Test
    void dates_future400_pastOnlySuperAdmin_todayForAll() {
        Ids ids = enrollment();
        Long reg = fixtures.cashRegister(false);
        LocalDate past = TODAY.minusDays(1);
        LocalDate future = TODAY.plusDays(1);

        fixtures.loginAs(UserRole.ACCOUNTANT);
        // to'lov va preview
        assertCode(() -> payments.previewPayment(req(ids, reg, past)), "payment.date.pastNotAllowed", HttpStatus.FORBIDDEN);
        assertCode(() -> payments.createPayment(req(ids, reg, past), null), "payment.date.pastNotAllowed",
            HttpStatus.FORBIDDEN);
        assertCode(() -> payments.createPayment(req(ids, reg, future), null), "payment.date.future",
            HttpStatus.BAD_REQUEST);
        // kassa kirim/chiqim, xarajat
        assertCode(() -> cash.addIncome(reg, income(past)), "payment.date.pastNotAllowed", HttpStatus.FORBIDDEN);
        assertCode(() -> cash.addIncome(reg, income(future)), "payment.date.future", HttpStatus.BAD_REQUEST);
        assertCode(() -> cash.addExpense(reg, cashExpense(past)), "payment.date.pastNotAllowed", HttpStatus.FORBIDDEN);
        assertCode(() -> finance.createExpense(expense(past)), "payment.date.pastNotAllowed", HttpStatus.FORBIDDEN);
        assertCode(() -> finance.createExpense(expense(future)), "payment.date.future", HttpStatus.BAD_REQUEST);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payments", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_transactions", Long.class)).isZero();

        // bugun — hamma rollar (ACC); sana berilmasa ham bugun
        cash.addIncome(reg, income(TODAY));
        cash.addIncome(reg, income(null));
        finance.createExpense(expense(TODAY));
        assertThat(payments.createPayment(req(ids, reg, TODAY), null).getId()).isNotNull();

        // SA — o'tgan sana mumkin, kelajak baribir 400
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        cash.addExpense(reg, cashExpense(past));
        finance.createExpense(expense(past));
        assertCode(() -> cash.addExpense(reg, cashExpense(future)), "payment.date.future", HttpStatus.BAD_REQUEST);
        Ids other = enrollment();
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        assertThat(payments.createPayment(req(other, reg, past), null).getPaymentDate()).isEqualTo(past);
    }

    @Test
    void pastDate_http403_localized() throws Exception {
        Long reg = fixtures.cashRegister(false);
        fixtures.loginAs(UserRole.ACCOUNTANT);
        String body = "{\"amount\":1000,\"paymentMethod\":\"CASH\",\"transactionDate\":\"2026-09-14\","
            + "\"transactionType\":\"Boshqa kirim\"}";
        for (String[] lang : new String[][]{{"uz", "faqat SUPER_ADMIN"}, {"ru", "только SUPER_ADMIN"},
                {"en", "Only SUPER_ADMIN"}}) {
            mvc.perform(post("/api/cash-registers/{id}/income", reg).header("Accept-Language", lang[0])
                    .with(user("test-accountant").roles("ACCOUNTANT"))
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("payment.date.pastNotAllowed"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(lang[1])));
        }
    }

    /** docs/ops/future-dated.sql (read-only) — qoida kiritilishidan oldingi kelajak sanali yozuvlar. Faqat PG. */
    @Test
    void futureDatedSql_findsFuturePaymentsAndCashRows() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.TRUE.equals(jdbc.execute(
            (org.springframework.jdbc.core.ConnectionCallback<Boolean>) c -> c.getMetaData().getDatabaseProductName()
                .toLowerCase(java.util.Locale.ROOT).contains("postgres"))), "future-dated.sql — PostgreSQL (pgtest)");
        Ids ids = enrollment();
        Long reg = fixtures.cashRegister(false);
        fixtures.loginAs(UserRole.ACCOUNTANT);
        Long paymentId = payments.createPayment(req(ids, reg, null), null).getId();
        cash.addIncome(reg, income(null));
        // Qoida kiritilishidan oldin yozilgan holat: sana haqiqiy bugundan keyin
        jdbc.update("UPDATE payments SET payment_date = CURRENT_DATE + 3 WHERE id = ?", paymentId);
        jdbc.update("UPDATE cash_transactions SET transaction_date = CURRENT_DATE + 3 WHERE payment_id = ?", paymentId);

        String sql = java.nio.file.Files.readString(java.nio.file.Path.of("docs/ops/future-dated.sql"));
        java.util.List<String> statements = java.util.Arrays.stream(sql.split(";\\s*\\n"))
            .map(s -> s.lines().filter(l -> !l.trim().startsWith("--")).reduce("", (a, b) -> a + "\n" + b).trim())
            .filter(s -> !s.isEmpty()).toList();
        assertThat(statements).hasSize(3);
        Map<String, Object> payRow = jdbc.queryForList(statements.get(0)).stream()
            .filter(r -> "payments".equals(r.get("jadval"))).findFirst().orElseThrow();
        assertThat(((Number) payRow.get("soni")).longValue()).isEqualTo(1);
        assertThat(jdbc.queryForList(statements.get(1))).extracting(r -> ((Number) r.get("id")).longValue())
            .containsExactly(paymentId);
        assertThat(jdbc.queryForList(statements.get(2))).hasSize(1);   // to'lov kirimi; qo'lda kirim — bugun
    }

    // ── TASK 2: tahrirlash / bekor qilish ────────────────────────────────

    @Test
    void cancel_nonSuperAdmin403Coded_securityAndService_superAdminAudited() throws Exception {
        Ids ids = enrollment();
        Long reg = fixtures.cashRegister(false);
        fixtures.loginAs(UserRole.ACCOUNTANT);
        Long paymentId = payments.createPayment(req(ids, reg, null), null).getId();

        // Servis darajasi (SecurityConfig chetlab chaqirilsa ham)
        fixtures.loginAs(UserRole.ADMIN);
        assertCode(() -> payments.cancelPayment(paymentId, "Xato kiritildi"), "payment.edit.superAdminOnly",
            HttpStatus.FORBIDDEN);
        SecurityContextHolder.clearContext();

        // SecurityConfig: ADMIN, ACC, SALES — 403 kod bilan (uz/ru)
        String url = "/api/payments/" + paymentId + "/cancel";
        for (String role : new String[]{"ADMIN", "ACCOUNTANT", "SALES_HEAD", "SALES_MANAGER"}) {
            mvc.perform(post(url).with(as(role)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"Xato kiritildi\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("payment.edit.superAdminOnly"));
        }
        mvc.perform(post(url).with(as("ADMIN")).header("Accept-Language", "ru")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("только SUPER_ADMIN")));
        // Kelajakdagi PUT/PATCH ham yopiq
        mvc.perform(put("/api/payments/" + paymentId).with(as("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("payment.edit.superAdminOnly"));
        // Oylik bekor qilish ham
        mvc.perform(post("/api/payroll/1/cancel").with(as("ACCOUNTANT")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Xato\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("payment.edit.superAdminOnly"));

        // SA — sabab majburiy, keyin bekor qilinadi va audit'da eski → yangi
        mvc.perform(post(url).with(user("test-super_admin").roles("SUPER_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("payment.cancel.reasonRequired"));
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        payments.cancelPayment(paymentId, "Xato kiritildi");
        long deadline = System.currentTimeMillis() + 5000;          // audit @Async — commit'dan keyin
        while (System.currentTimeMillis() < deadline && jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = 'PAYMENT_CANCEL'", Integer.class) == 0) {
            Thread.sleep(50);
        }
        Map<String, Object> audit = jdbc.queryForMap("SELECT username, action, summary, details_json FROM audit_logs"
            + " WHERE entity_type = 'Payment' AND action = 'PAYMENT_CANCEL'");
        assertThat(audit.get("username")).isEqualTo("test-super_admin");
        assertThat(audit.get("summary").toString()).contains("Xato kiritildi");
        assertThat(audit.get("details_json").toString())
            .contains("\"field\":\"status\"").contains("\"old\":\"PAID\"").contains("\"new\":\"CANCELLED\"")
            .contains("\"field\":\"cancelReason\"").contains("\"new\":\"Xato kiritildi\"");
    }
}
