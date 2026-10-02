package com.crm.entity.enums;

import java.util.Locale;

/**
 * Payroll holati — docs/design/payroll-v2.md §1.
 * <pre>
 * DRAFT → APPROVED → PAID;  APPROVED/PAID → CANCELLED (SA);  DRAFT → o'chiriladi
 * </pre>
 * Faqat DRAFT qayta hisoblanadi; APPROVED va PAID summasi hech qachon qayta yozilmaydi.
 */
public enum PayrollStatus {
    DRAFT,
    APPROVED,
    PAID,
    CANCELLED;

    /**
     * Bazadan o'qish: v1 dagi {@code PENDING} va V54 gacha qolgan har qanday erkin matn — DRAFT
     * (V54 ham xuddi shunday normallashtiradi). O'qish hech qachon yiqilmasin.
     */
    public static PayrollStatus fromDb(String value) {
        PayrollStatus parsed = parseOrNull(value);
        return parsed != null ? parsed : DRAFT;
    }

    /** Ro'yxat filtri: {@code PENDING} → DRAFT; noma'lum qiymat — null (400 chaqiruvchida). */
    public static PayrollStatus parseOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim().toUpperCase(Locale.ROOT);
        if (v.equals("PENDING")) {
            return DRAFT;
        }
        try {
            return valueOf(v);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
