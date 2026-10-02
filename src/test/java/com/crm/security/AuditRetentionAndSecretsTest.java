package com.crm.security;

import com.crm.audit.AuditRetentionJob;
import com.crm.service.TelegramService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Q14: audit saqlash muddati (moliya 365, qolgani 180); bo'sh Telegram tokeni — xatosiz o'chiq. */
class AuditRetentionAndSecretsTest extends Phase5ItBase {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 2);

    @Autowired
    AuditRetentionJob retentionJob;

    private void log(String summary, String action, String entityType, int daysAgo) {
        jdbc.update("INSERT INTO audit_logs (action, entity_type, summary, created_at) VALUES (?, ?, ?, ?)",
            action, entityType, summary, TODAY.minusDays(daysAgo).atTime(12, 0));
    }

    @Test
    void financialKept365_regularKept180() {
        log("lead-100", "CREATE", "Lead", 100);
        log("lead-200", "STATUS_CHANGE", "Lead", 200);
        log("login-190", "LOGIN_FAILED", null, 190);          // entity_type NULL — oddiy
        log("payment-200", "PAYMENT", "Payment", 200);
        log("payment-400", "PAYMENT", "Payment", 400);
        log("refund-300", "REFUND", "Student", 300);          // amal bo'yicha moliyaviy
        log("payroll-300", "UPDATE", "Payroll", 300);         // obyekt bo'yicha moliyaviy
        log("balance-370", "UPDATE", "Balance", 370);
        log("student-300", "UPDATE", "Student", 300);         // o'quvchi tahriri — oddiy

        retentionJob.purge(TODAY);

        List<String> left = jdbc.queryForList("SELECT summary FROM audit_logs ORDER BY summary", String.class);
        assertThat(left).containsExactly("lead-100", "payment-200", "payroll-300", "refund-300");
    }

    @Test
    void blankTelegramToken_sendsNothing_noError() {
        TelegramService telegram = new TelegramService();
        ReflectionTestUtils.setField(telegram, "botToken", "");
        ReflectionTestUtils.setField(telegram, "enabled", true);
        ReflectionTestUtils.invokeMethod(telegram, "logState");
        assertThat(telegram.sendMessage("12345", "salom")).isFalse();
    }
}
