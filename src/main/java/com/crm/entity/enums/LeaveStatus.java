package com.crm.entity.enums;

import java.util.Locale;

/**
 * Ta'til holati (leaves-exams-contracts §1.2):
 * <pre>
 * PENDING → APPROVED (paid majburiy) / REJECTED (izoh bilan);  PENDING/APPROVED → CANCELLED
 * </pre>
 */
public enum LeaveStatus {
    PENDING,
    APPROVED,
    REJECTED,
    CANCELLED;

    public static LeaveStatus parseOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Bazadan: noma'lum eski matn — PENDING (V61 ham shunday yozadi va NOTICE beradi). */
    public static LeaveStatus fromDb(String value) {
        LeaveStatus s = parseOrNull(value);
        return s != null ? s : PENDING;
    }
}
