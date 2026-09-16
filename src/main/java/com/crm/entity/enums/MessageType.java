package com.crm.entity.enums;

import java.util.Locale;

/**
 * Xabar turi. 1-bosqichda faqat {@link #TEXT} va {@link #SYSTEM} yoziladi.
 *
 * <p>{@link #SYSTEM} — foydalanuvchi yozmagan, tizim qo'ygan xabar
 * (guruh yaratildi, ishtirokchi qo'shildi). Matni bor, lekin frontend uni
 * boshqacha ko'rsatadi.
 *
 * <p>{@code IMAGE}, {@code FILE}, {@code VOICE} keyingi bosqichda shu
 * yerga qo'shiladi — ustun matn bo'lgani uchun migratsiya kerak bo'lmaydi.
 */
public enum MessageType {

    TEXT,
    SYSTEM;

    /** Matnni qiymatga o'giradi; tanilmasa null. */
    public static MessageType parseOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Bazadan o'qishda ishlatiladi — tanilmagan qiymat TEXT bo'ladi. */
    public static MessageType fromString(String raw) {
        MessageType parsed = parseOrNull(raw);
        return parsed != null ? parsed : TEXT;
    }
}
