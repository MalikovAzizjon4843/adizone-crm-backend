package com.crm.entity.enums;

import java.util.Locale;

/**
 * Suhbat turi.
 *
 * <p>{@link #DIRECT} — ikki kishilik yozishma; {@code title} yo'q, nom
 * suhbatdoshdan olinadi. {@link #GROUP} — uch va undan ortiq ishtirokchi,
 * {@code title} majburiy.
 */
public enum ConversationType {

    DIRECT,
    GROUP,
    /**
     * Mini App foydalanuvchisi (o'quvchi/ota-ona) bilan suhbat (telegram-platform §11.3): ishtirokchilar — faqat
     * xodimlar, app tomoni {@code Conversation.externalIdentityId}. {@code title} — app foydalanuvchisi nomi.
     */
    EXTERNAL;

    /** Matnni qiymatga o'giradi; tanilmasa null. */
    public static ConversationType parseOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Bazadan o'qishda ishlatiladi — tanilmagan qiymat DIRECT bo'ladi. */
    public static ConversationType fromString(String raw) {
        ConversationType parsed = parseOrNull(raw);
        return parsed != null ? parsed : DIRECT;
    }
}
