package com.crm.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** {@code app.payroll.*} — docs/design/payroll-v2.md §11 qarorlari. */
@Component
@ConfigurationProperties(prefix = "app.payroll")
@Getter
@Setter
public class PayrollProperties {

    /**
     * Billing v2 cutover sanasi (§11 #1). Shu sana oyidan OLDINGI oylar uchun hisob/generate —
     * 400 {@code payroll.beforeCutover}; cutover oyining o'zi — {@code estimated = true}.
     * Berilmasa — qo'llangan (APPLIED) billing migratsiyasining eng erta {@code cutover_date} i;
     * u ham bo'lmasa — cheklov yo'q.
     */
    private LocalDate cutoverDate;

    /**
     * §11 #7: false (default) — davr faqat real PAYMENT krediti ishtirok etgan bo'lsa "to'lagan";
     * DISCOUNT/BONUS bilan to'liq yopilgan davr sanalmaydi. true — har qanday kredit bilan yopilgan.
     */
    private boolean countDiscountCovered = false;
}
