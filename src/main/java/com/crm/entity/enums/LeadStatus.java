package com.crm.entity.enums;

import java.util.EnumSet;
import java.util.Set;

public enum LeadStatus {
    NEW,
    DAY_1_WORKED,
    DAY_2_WORKED,
    DAY_3_WORKED,
    DAY_4_WORKED,
    ONLINE_ENROLLED,
    OFFLINE_ENROLLED,
    ONLINE_PAID,
    OFFLINE_PAID,
    CONVERTED,
    REJECTED;

    /**
     * Yopilgan bosqichlar — lid ustida ish tugagan. Ochiq lidlarni sanaydigan
     * joylarda ro'yxatni qo'lda takrorlamaslik uchun shu yerda.
     *
     * <p>Voronka dinamik bo'lganda bu to'plam {@code LeadStage.systemCode}
     * (WON/LOST) bilan almashtiriladi — o'sha paytda o'zgartirish kerak
     * bo'lgan yagona joy.
     */
    private static final Set<LeadStatus> CLOSED = EnumSet.of(CONVERTED, REJECTED);

    /** Lid ustida ish tugaganmi? */
    public boolean isClosed() {
        return CLOSED.contains(this);
    }

    /** Yopilgan bosqichlar — so'rov parametri sifatida ishlatish uchun. */
    public static Set<LeadStatus> closed() {
        return CLOSED;
    }

    public static LeadStatus fromString(String value) {
        if (value == null || value.isBlank()) {
            return NEW;
        }
        String normalized = value.trim().toUpperCase();
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException e) {
            return fromLegacy(normalized);
        }
    }

    public static LeadStatus fromLegacy(String legacyStatus) {
        if (legacyStatus == null || legacyStatus.isBlank()) {
            return NEW;
        }
        return switch (legacyStatus.trim().toUpperCase()) {
            case "CONTACTED" -> DAY_1_WORKED;
            case "IN_PROGRESS" -> DAY_2_WORKED;
            case "INTERESTED" -> DAY_3_WORKED;
            case "ENROLLED" -> CONVERTED;
            default -> NEW;
        };
    }
}
