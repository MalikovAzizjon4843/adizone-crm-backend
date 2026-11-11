package com.crm.entity.enums;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Yozilma yopilish sababi (director-dashboard §3.4, §7 #12). {@code exit_reason} erkin
 * matni saqlanadi; tahlil shu kod bo'yicha. JSON da nomi ({@code PRICE}) chiqadi; o'zbekcha
 * nomi — {@link #getLabel()} (alohida {@code exitReasonLabel} maydonlari).
 */
public enum ExitReasonCode {
    TRANSFERRED("Boshqa guruhga ko'chirildi"),
    FROZEN("Muzlatildi"),
    AUTO_ARCHIVE("Avto-arxiv"),
    GRADUATED("Bitirdi"),
    PRICE("Narx"),
    SCHEDULE("Dars vaqti to'g'ri kelmadi"),
    MOVED_AWAY("Boshqa joyga ko'chib ketdi"),
    QUALITY("Ta'lim sifati"),
    HEALTH("Sog'lig'i"),
    NO_TIME("Vaqti yo'q"),
    OTHER("Boshqa");

    /** Aniq ketish sabablari (o'quvchi oqimi: "sabab bilan ketdi"). */
    public static final Set<ExitReasonCode> SPECIFIC_CHURN =
        EnumSet.of(PRICE, SCHEDULE, MOVED_AWAY, QUALITY, HEALTH, NO_TIME);

    private final String label;

    ExitReasonCode(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

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
