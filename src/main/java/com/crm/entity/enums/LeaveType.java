package com.crm.entity.enums;

import java.util.Locale;

/**
 * Ta'til turi (leaves-exams-contracts §1.1). Haqli/haqsizni tur emas, tasdiqlashdagi {@code paid}
 * belgilaydi (D2) — tur faqat hisobot va ko'rinish uchun.
 */
public enum LeaveType {
    ANNUAL,
    SICK,
    FAMILY,
    STUDY,
    OTHER;

    /** So'rovdan: noma'lum qiymat — null (chaqiruvchi 400 qaytaradi). Eski frontend nomlari ham tushuniladi. */
    public static LeaveType parseOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim().toUpperCase(Locale.ROOT);
        return switch (v) {
            case "VACATION", "YILLIK", "MEHNAT" -> ANNUAL;
            case "SICKNESS", "ILLNESS", "MEDICAL", "KASALLIK" -> SICK;
            case "PERSONAL", "OILAVIY" -> FAMILY;
            case "EDUCATION", "O'QISH" -> STUDY;
            default -> {
                try {
                    yield valueOf(v);
                } catch (IllegalArgumentException e) {
                    yield null;
                }
            }
        };
    }

    /** Bazadan o'qish hech qachon yiqilmasin: V61 gacha qolgan erkin matn — OTHER. */
    public static LeaveType fromDb(String value) {
        LeaveType t = parseOrNull(value);
        return t != null ? t : OTHER;
    }
}
