package com.crm.entity.enums;

import java.util.Locale;

/**
 * Yozilma yopilish sababi (director-dashboard §3.4, §7 #12). {@code exit_reason} erkin
 * matni saqlanadi; tahlil shu kod bo'yicha.
 */
public enum ExitReasonCode {
    TRANSFERRED,
    FROZEN,
    AUTO_ARCHIVE,
    GRADUATED,
    PRICE,
    SCHEDULE,
    MOVED_AWAY,
    QUALITY,
    HEALTH,
    NO_TIME,
    OTHER;

    /** Eski front {@code reason} matni → kod: tanilgan qiymat o'zi, qolgani OTHER (§7 #12). */
    public static ExitReasonCode fromLegacy(String reason) {
        if (reason == null || reason.isBlank()) {
            return OTHER;
        }
        try {
            return valueOf(reason.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return OTHER;
        }
    }

    /** Retention'da churn hisoblanmaydigan sabablar (§1.6). */
    public boolean isChurn() {
        return this != TRANSFERRED && this != FROZEN && this != GRADUATED;
    }
}
