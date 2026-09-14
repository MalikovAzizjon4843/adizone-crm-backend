package com.crm.entity.enums;

import java.util.Locale;

/**
 * Bosqichning tizimdagi roli. Nomi va rangi buyurtmachiga tegishli,
 * TURI esa kodga: konvert va rad etish oqimlari aynan shu belgiga
 * tayanadi, bosqich nomi qanday o'zgartirilsa ham.
 *
 * <p>{@link #CONVERTED} va {@link #REJECTED} bosqichlari o'chirilmaydi.
 */
public enum StageKind {

    /** Ish davom etayotgan oraliq bosqich. Yangi bosqichlar doim shunday. */
    OPEN,
    /** Lid o'quvchiga aylandi — yakuniy, muvaffaqiyatli. */
    CONVERTED,
    /** Lid rad etildi — yakuniy, muvaffaqiyatsiz. */
    REJECTED;

    public boolean isFinal() {
        return this != OPEN;
    }

    public static StageKind parseOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Bazadan o'qishda — tanilmagan qiymat OPEN bo'ladi, istisno tashlanmaydi. */
    public static StageKind fromString(String raw) {
        StageKind parsed = parseOrNull(raw);
        return parsed != null ? parsed : OPEN;
    }
}
