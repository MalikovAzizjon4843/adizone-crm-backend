package com.crm.entity.enums;

import java.util.Locale;

/**
 * Meta formasining savoli CRM dagi qaysi maydonga tushishi.
 *
 * <p>Mapping har bir savol uchun ALOHIDA saqlanadi
 * ({@code meta_lead_form_questions.crm_field}), chunki formalar bir xil
 * ma'noni turli kalitlar bilan so'raydi: {@code phone_number} (Meta
 * standart savoli) va {@code telefon_raqamingiz} (qo'lda yozilgan savol)
 * bitta formada birga uchraydi.
 *
 * <p>{@link #IGNORE} — savol bor, lekin CRM ga kerak emas (masalan
 * "shartlarga rozimisiz?"). {@code null} esa "hali sozlanmagan" degani va
 * sinxronizatsiya uni avtomatik taxmin bilan to'ldiradi.
 */
public enum MetaCrmField {

    /** To'liq ism bitta maydonda — Meta ning {@code full_name} savoli. */
    FULL_NAME,
    FIRST_NAME,
    LAST_NAME,
    /** Birlamchi telefon — odatda Meta ning {@code phone_number} savoli. */
    PHONE,
    /** Zaxira telefon — qo'lda yozilgan savol, kodsiz kelishi mumkin. */
    PHONE_ALT,
    /** "Qachon qo'ng'iroq qilaylik" — avtomatik vazifa vaqti shundan olinadi. */
    PREFERRED_CALL_TIME,
    /** "Qachondan boshlamoqchisiz" — izohga yoziladi. */
    PLANNED_START,
    /** "Nima maqsadda o'qiysiz" — izohga yoziladi. */
    PURPOSE,
    /** Boshqa har qanday javob — izohga yoziladi. */
    NOTE,
    /** CRM ga umuman yozilmaydi. */
    IGNORE;

    public static MetaCrmField parseOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
