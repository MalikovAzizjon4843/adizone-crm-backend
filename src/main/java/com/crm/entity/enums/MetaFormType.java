package com.crm.entity.enums;

import java.util.Locale;

/**
 * Forma qanday lid keltiradi. Bu forma sozlamasi, Meta dan kelmaydi —
 * operator {@code PUT /api/meta/forms/{formId}} orqali qo'yadi.
 *
 * <p>Yangi forma doim {@link #UNMAPPED} bo'lib tushadi va lid baribir
 * yaratiladi: noto'g'ri sozlama tufayli lid yo'qolishidan ko'ra, uni
 * keyin qo'lda saralash arzon.
 */
public enum MetaFormType {

    /** O'quv kursiga qiziqqan odam — oddiy lid. */
    STUDENT,
    /** Ishga ariza. Hozircha lid yaratilmaydi, xom JSON saqlanadi. */
    HR,
    /** Test/spam forma — butunlay e'tiborsiz qoldiriladi. */
    IGNORE,
    /** Hali sozlanmagan. Lid yaratiladi, lekin sozlash kerakligi ko'rinib turadi. */
    UNMAPPED;

    public static MetaFormType parseOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Bazadan o'qishda — tanilmagan qiymat UNMAPPED, istisno tashlanmaydi. */
    public static MetaFormType fromString(String raw) {
        MetaFormType parsed = parseOrNull(raw);
        return parsed != null ? parsed : UNMAPPED;
    }
}
