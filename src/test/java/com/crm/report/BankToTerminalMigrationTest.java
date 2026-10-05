package com.crm.report;

import com.crm.billing.AccrualService;
import com.crm.billing.support.AbstractBillingIT;
import com.crm.dto.request.IncomeCreateDto;
import com.crm.dto.request.PaymentRequest;
import com.crm.entity.enums.PaymentMethod;
import com.crm.entity.enums.UserRole;
import com.crm.service.CashRegisterService;
import com.crm.service.PaymentService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** V78 (buyurtmachi qarori 2026-10-05): eski BANK yozuvlari → TERMINAL, audit yozuvi, idempotent. Faqat PostgreSQL. */
class BankToTerminalMigrationTest extends AbstractBillingIT {

    private static final String V78 = "db/migration/V78__bank_to_terminal.sql";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 15);

    @Autowired CashRegisterService cash;
    @Autowired PaymentService payments;
    @Autowired AccrualService accrual;
    @Autowired JdbcTemplate jdbc;

    @Test
    void legacyBankRows_becomeTerminal_auditLogged_balancesUnchanged_idempotent() {
        Assumptions.assumeTrue(isPostgres(), "V78 — PostgreSQL skripti (pgtest)");
        Long reg = fixtures.cashRegister(true);
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        IncomeCreateDto in = new IncomeCreateDto();
        in.setAmount(BigDecimal.valueOf(500_000));
        in.setPaymentMethod(PaymentMethod.TERMINAL);
        in.setTransactionDate(DAY);
        in.setTransactionType("Boshqa kirim");
        cash.addIncome(reg, in);

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
        r.setPaymentMethod(PaymentMethod.TERMINAL);
        Long paymentId = payments.createPayment(r, null).getId();

        // V78 dan oldingi holat: eski kod BANK yozgan
        jdbc.update("UPDATE cash_transactions SET payment_method = 'BANK' WHERE cash_register_id = ?", reg);
        jdbc.update("UPDATE payments SET payment_method = 'BANK' WHERE id = ?", paymentId);
        Map<String, Object> before = jdbc.queryForMap(
            "SELECT balance, cash_balance, plastic_balance FROM cash_registers WHERE id = ?", reg);
        assertThat(bankCount()).isEqualTo(3);                       // 2 kassa kirimi + 1 to'lov

        runScript(V78);

        assertThat(bankCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_transactions WHERE payment_method = 'TERMINAL'",
            Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT payment_method FROM payments WHERE id = ?", String.class, paymentId))
            .isEqualTo("TERMINAL");
        assertThat(jdbc.queryForMap("SELECT balance, cash_balance, plastic_balance FROM cash_registers WHERE id = ?",
            reg)).isEqualTo(before);
        Map<String, Object> audit = jdbc.queryForMap(
            "SELECT action, entity_type, summary, details_json FROM audit_logs WHERE username = 'V78'");
        assertThat(audit.get("action")).isEqualTo("UPDATE");
        assertThat(audit.get("summary").toString()).contains("BANK -> TERMINAL (3 ta yozuv)");
        assertThat(audit.get("details_json").toString())
            .contains("\"payments\": {\"ids\": \"" + paymentId + "\", \"count\": 1}")
            .contains("\"cash_transactions\"");

        runScript(V78);                                              // qayta — o'zgarish ham, yangi audit ham yo'q
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE username = 'V78'", Long.class))
            .isEqualTo(1);
    }

    private long bankCount() {
        return jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM payments WHERE payment_method = 'BANK')"
            + " + (SELECT COUNT(*) FROM cash_transactions WHERE payment_method = 'BANK')"
            + " + (SELECT COUNT(*) FROM payroll WHERE payment_method = 'BANK')", Long.class);
    }

    private boolean isPostgres() {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) c ->
            c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgres")));
    }

    private void runScript(String path) {
        jdbc.execute((ConnectionCallback<Void>) c -> {
            ScriptUtils.executeSqlScript(c, new EncodedResource(new ClassPathResource(path)),
                false, false, ScriptUtils.DEFAULT_COMMENT_PREFIX, ScriptUtils.EOF_STATEMENT_SEPARATOR,
                ScriptUtils.DEFAULT_BLOCK_COMMENT_START_DELIMITER, ScriptUtils.DEFAULT_BLOCK_COMMENT_END_DELIMITER);
            return null;
        });
    }
}
