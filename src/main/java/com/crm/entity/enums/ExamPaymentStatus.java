package com.crm.entity.enums;

import java.util.Locale;

/**
 * Imtihon to'lovi holati (§4.1): FREE — bepul imtihon; PAID — yozilishda kassaga tushgan;
 * REFUNDED — yozilish bekor qilinib, kassaga REVERSAL yozilgan. PENDING yo'q — pullik imtihonga
 * to'lovsiz yozilib bo'lmaydi (D9).
 */
public enum ExamPaymentStatus {
    FREE,
    PAID,
    REFUNDED;

    /** Eski {@code PENDING} (preview davri, kassasiz) va noma'lum matn — FREE (V62 ham shunday yozadi). */
    public static ExamPaymentStatus fromDb(String value) {
        if (value == null || value.isBlank()) {
            return FREE;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return FREE;
        }
    }
}
