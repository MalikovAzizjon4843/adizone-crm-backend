package com.crm.entity.enums;

import java.util.Locale;

/**
 * Vazifa holati. {@link #OPEN} — bajarilishi kutilayotgan yagona holat;
 * voronka kartasidagi rangli nuqta va barcha hisoblar faqat shu holatni sanaydi.
 *
 * <p>{@link #CANCELLED} — vazifa keraksiz bo'lib qolgan (lid rad etilgan va h.k.).
 * {@link #DONE} dan farqi: natija ({@code result}) talab qilinmaydi va
 * "bajarildi" statistikasiga kirmaydi.
 */
public enum TaskStatus {

    OPEN("Ochiq"),
    DONE("Bajarildi"),
    CANCELLED("Bekor qilindi");

    private final String label;

    TaskStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public boolean isOpen() {
        return this == OPEN;
    }

    /** Matnni qiymatga o'giradi; tanilmasa null. */
    public static TaskStatus parseOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Bazadan o'qishda ishlatiladi — tanilmagan qiymat OPEN bo'ladi. */
    public static TaskStatus fromString(String raw) {
        TaskStatus parsed = parseOrNull(raw);
        return parsed != null ? parsed : OPEN;
    }
}
