package com.crm.util;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * "Qayerdan keldi" qiymatlari — {@code leads.source} va {@code students.source} (V79).
 *
 * <p>{@code leads.source} — erkin VARCHAR(30): ochiq forma va kanban {@code source} ni katta harfga
 * o'girib saqlaydi (standart {@code WEBSITE}), Meta — forma {@code default_source} yoki platformadan
 * {@code INSTAGRAM}/{@code FACEBOOK}, Excel import — teglardan {@code WEBSITE}/{@code INSTAGRAM}/{@code OTHER}.
 * Shuning uchun {@code students.source} ham matn: lid konvertatsiyasida qiymat AYNAN ko'chiriladi
 * ({@link #fromLead}), ro'yxatda bo'lmasa ham.
 *
 * <p>Qo'lda yaratish/tahrirlashda esa faqat {@link #STUDENT_SOURCES} qabul qilinadi: lid qiymatlari
 * ({@code MarketingSource} nomlari + {@code WEBSITE}) va o'quvchi uchun {@code REFERRAL}, {@code WALK_IN},
 * {@code OTHER}. Manbasiz (null) — analitikada {@link #UNKNOWN}.
 */
public final class SourceCatalog {

    /** Manba yo'q (null/bo'sh) — analitika qatori va filtr qiymati, bazaga yozilmaydi. */
    public static final String UNKNOWN = "UNKNOWN";

    public static final int MAX_LENGTH = 30;

    public static final Set<String> STUDENT_SOURCES = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
        "WEBSITE", "INSTAGRAM", "FACEBOOK", "TELEGRAM", "YOUTUBE", "TARGET", "SELF_CALL",
        "FORMER_STUDENT", "OFFLINE", "LEAD", "REFERRAL", "WALK_IN", "OTHER")));

    private SourceCatalog() {
    }

    /** Katta harf, chetlari kesilgan; bo'sh bo'lsa null. */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    public static boolean isStudentSource(String normalized) {
        return normalized != null && STUDENT_SOURCES.contains(normalized);
    }

    /** Lid manbasi o'quvchiga — tekshiruvsiz, faqat normallashtirib va ustun uzunligida kesib. */
    public static String fromLead(String leadSource) {
        String value = normalize(leadSource);
        if (value == null) {
            return null;
        }
        return value.length() > MAX_LENGTH ? value.substring(0, MAX_LENGTH) : value;
    }

    /** Analitika kaliti: null/bo'sh → {@link #UNKNOWN}. */
    public static String keyOf(String source) {
        String value = normalize(source);
        return value != null ? value : UNKNOWN;
    }
}
