package com.crm.entity.enums;

import java.util.Locale;

/**
 * Vazifa turi. amoCRM'dagi "Связаться" analogi — {@link #CALL}.
 *
 * <p>Baza ustuni VARCHAR: yangi tur qo'shilsa migratsiya kerak emas
 * ({@code EnumCheckConstraintCleaner} CHECK constraintlarni tozalaydi).
 * Noma'lum qiymat {@link #OTHER} ga aylanadi — bu ilovani yiqitmaydi.
 */
public enum TaskType {

    CALL("Qo'ng'iroq", "📞"),
    MEETING("Uchrashuv", "🤝"),
    MESSAGE("Xabar", "💬"),
    OTHER("Boshqa", "📌");

    private final String label;
    private final String icon;

    TaskType(String label, String icon) {
        this.label = label;
        this.icon = icon;
    }

    public String getLabel() {
        return label;
    }

    public String getIcon() {
        return icon;
    }

    /** Matnni qiymatga o'giradi; tanilmasa null. */
    public static TaskType parseOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_'));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Bazadan o'qishda ishlatiladi — tanilmagan qiymat OTHER bo'ladi. */
    public static TaskType fromString(String raw) {
        TaskType parsed = parseOrNull(raw);
        return parsed != null ? parsed : OTHER;
    }
}
