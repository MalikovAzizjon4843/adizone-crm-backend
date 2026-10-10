package com.crm.entity.enums;

import java.util.Locale;

/**
 * Dars turi (payroll v3, buyurtmachi qarori 2026-10-10): o'qituvchining o'quvchi boshiga ulushi shunga qarab —
 * GROUP ({@code salary_rules.per_student_fee}) yoki INDIVIDUAL ({@code salary_rules.per_student_individual}).
 * Manba — {@code groups.lesson_type}; yangi guruhda standart {@code courses.lesson_type} dan;
 * {@code billing_periods.lesson_type} — accrual paytidagi nusxa.
 */
public enum LessonType {

    GROUP,
    INDIVIDUAL;

    public static LessonType parseOrNull(String raw) {
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
