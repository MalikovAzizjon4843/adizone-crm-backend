package com.crm.entity.enums;

import java.util.Locale;

/** Imtihonga yozilish holati (leaves-exams-contracts §4.1). */
public enum ExamRegistrationStatus {
    REGISTERED,
    CANCELLED,
    ATTENDED,
    ABSENT;

    /** Bazadan o'qish hech qachon yiqilmasin: noma'lum eski matn — REGISTERED (V62 ham shunday yozadi). */
    public static ExamRegistrationStatus fromDb(String value) {
        if (value == null || value.isBlank()) {
            return REGISTERED;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return REGISTERED;
        }
    }
}
