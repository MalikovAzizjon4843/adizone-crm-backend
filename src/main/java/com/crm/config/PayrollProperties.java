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

    /**
     * Payroll v3 (payroll-v2.md §14, buyurtmachi qarori 2026-10-10) shu sana OYIDAN kuchga kiradi: dars turi bo'yicha
     * ulush, chala to'lov ulushi, stavka, KPI pog'onalari va yangi "faol" ta'rifi. Undan oldingi oylar — v2 formula
     * (tasdiqlangan oyliklar va ularning qayta hisobi o'zgarmaydi). null — hamma oylar v3.
     */
    private LocalDate v3From = LocalDate.of(2026, 10, 1);
}
