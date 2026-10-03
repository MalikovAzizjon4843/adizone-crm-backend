package com.crm.entity.enums;

import java.util.Locale;

/**
 * O'quvchining uy vazifasi holati — o'qituvchi belgilaydi (o'quvchi login qilmaydi), phase6-api §4.
 * Eski {@code PENDING} — {@code NOT_SUBMITTED} (V65).
 */
public enum HomeworkSubmissionStatus {
    SUBMITTED,
    LATE,
    NOT_SUBMITTED;

    /** Bazadagi qiymat → enum; noma'lum/eski (PENDING, null) — NOT_SUBMITTED. */
    public static HomeworkSubmissionStatus fromDb(String raw) {
        if (raw == null) {
            return NOT_SUBMITTED;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return NOT_SUBMITTED;
        }
    }

    /** So'rovdagi qiymat; noma'lum — null (servis 400 beradi). */
    public static HomeworkSubmissionStatus parseOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
