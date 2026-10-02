package com.crm.audit;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "app.audit")
@Getter
@Setter
public class AuditProperties {

    /** false bo'lsa aspect umuman yuklanmaydi (@ConditionalOnProperty). */
    private boolean enabled = true;

    /**
     * Oddiy yozuvlar shu kundan keyin kechasi o'chiriladi (phase5-audit Q14: 180).
     * 0 yoki manfiy — hech qachon o'chirilmaydi.
     */
    private int retentionDays = 180;

    /** Moliyaviy yozuvlar (pastdagi amal yoki obyekt turlari) — Q14: 365. 0 yoki manfiy — hech qachon. */
    private int financialRetentionDays = 365;

    /** Shu amallardagi yozuv — moliyaviy (obyekt turidan qat'i nazar, masalan REFUND → Student). */
    private List<String> financialActions = new ArrayList<>(List.of(
        AuditAction.PAYMENT, AuditAction.PAYMENT_CANCEL, AuditAction.REFUND));

    /** Shu obyekt turlaridagi har qanday yozuv — moliyaviy. */
    private List<String> financialEntityTypes = new ArrayList<>(List.of(
        "Payment", "Payroll", "CashRegister", "CashTransaction", "Balance",
        "BonusPenalty", "SalaryRule", "Expense", "Income", "ExamRegistration"));
}
