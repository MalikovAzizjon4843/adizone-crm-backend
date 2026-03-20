package com.crm.entity.enums;

import java.util.EnumSet;
import java.util.Set;

public enum LeadStatus {
    NEW,
    /**
     * Lid bilan bog'lanilgan. Avvalgi DAY_1_WORKED..DAY_4_WORKED to'rtligining
     * o'rnini bosadi: "necha marta urinildi" endi vazifalar lentasida —
     * har qo'ng'iroq o'z sanasi va natijasi bilan turadi, kanbanda esa
     * to'rtta deyarli bo'sh ustun kerak emas.
     */
    CONTACTED,
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

    /**
     * Bazada qolib ketgan eski nomlarni tirik qiymatga o'giradi.
     *
     * <p>DAY_1..4_WORKED shu yerda ataylab saqlanib turibdi: ular enumdan
     * olib tashlanganda baza ko'chirildi, lekin ko'chirish o'tkazib yuborgan
     * ustun qolsa (masalan {@code lead_comments.status_at_comment}) yozuv
     * NEW ga emas, CONTACTED ga tushsin — ya'ni ma'no yo'qolmasin.
     * {@code LeadStatusConverter} bu metodga tayanadi va hech qachon
     * istisno tashlamaydi.
     */
    public static LeadStatus fromLegacy(String legacyStatus) {
        if (legacyStatus == null || legacyStatus.isBlank()) {
            return NEW;
        }
        return switch (legacyStatus.trim().toUpperCase()) {
            case "DAY_1_WORKED", "DAY_2_WORKED", "DAY_3_WORKED", "DAY_4_WORKED" -> CONTACTED;
            case "IN_PROGRESS", "INTERESTED" -> CONTACTED;
            case "ENROLLED" -> CONVERTED;
            default -> NEW;
        };
    }
}
