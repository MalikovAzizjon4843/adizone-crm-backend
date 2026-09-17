package com.crm.entity.enums;

import java.util.Locale;

/**
 * Xabar turi.
 *
 * <p>{@link #SYSTEM} — foydalanuvchi yozmagan, tizim qo'ygan xabar
 * (guruh yaratildi, ishtirokchi qo'shildi). Matni bor, lekin frontend uni
 * boshqacha ko'rsatadi.
 *
 * <p>{@link #IMAGE} va {@link #FILE} — biriktirmasi bor xabar. Ikkovining
 * farqi faqat ko'rinishda: rasmlar galereya bo'lib chiziladi, boshqa
 * fayllar esa nomi va hajmi bilan qator bo'lib. Matn ham bo'lishi mumkin
 * (rasm ostidagi izoh).
 *
 * <p>{@link #VOICE} — mikrofonga yozilgan ovoz. {@link #FILE} dan
 * ajratilgan, chunki u boshqacha ko'rinadi: ijro tugmasi, to'lqin
 * shakli va davomiyligi. Bunday xabarda biriktirma doim bitta.
 */
public enum MessageType {

    TEXT,
    IMAGE,
    FILE,
    VOICE,
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
