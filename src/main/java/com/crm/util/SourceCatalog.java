package com.crm.util;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /**
     * "Adizone Qo'ng'iroqlar" avtomatik lidi (V90, docs/design/calls.md §4.2) — yorlig'i "Qo'ng'iroq (ilova)". Faqat lid manbasi:
     * {@link #STUDENT_SOURCES} da yo'q (konvertatsiyada {@link #fromLead} baribir aynan ko'chiradi).
     */
    public static final String PHONE_CALL = "PHONE_CALL";

    public static final Set<String> STUDENT_SOURCES = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
        "WEBSITE", "INSTAGRAM", "FACEBOOK", "TELEGRAM", "YOUTUBE", "TARGET", "SELF_CALL",
        "FORMER_STUDENT", "OFFLINE", "LEAD", "REFERRAL", "WALK_IN", "OTHER")));

    /**
     * Lid manbalari ro'yxati ({@code GET /api/enums/lead-sources}): o'quvchi manbalari + {@link #PHONE_CALL}. Erkin matn
     * bo'lgani uchun bazada boshqa qiymat ham bo'lishi mumkin — ro'yxat filtr / tanlov uchun, cheklov emas.
     */
    public static final Set<String> LEAD_SOURCES;

    static {
        LinkedHashSet<String> lead = new LinkedHashSet<>(STUDENT_SOURCES);
        lead.add(PHONE_CALL);
        LEAD_SOURCES = Collections.unmodifiableSet(lead);
    }

    /**
     * Ikonka kodi (frontend uni o'z ikonka to'plamiga moslaydi): lucide nomi. Ro'yxatda bo'lmagan / noma'lum — {@code tag}.
     */
    private static final Map<String, String> ICONS = Map.ofEntries(
        Map.entry("WEBSITE", "globe"), Map.entry("INSTAGRAM", "instagram"), Map.entry("FACEBOOK", "facebook"),
        Map.entry("TELEGRAM", "send"), Map.entry("YOUTUBE", "youtube"), Map.entry("TARGET", "target"),
        Map.entry("SELF_CALL", "phone-call"), Map.entry("FORMER_STUDENT", "graduation-cap"),
        Map.entry("OFFLINE", "megaphone"), Map.entry("LEAD", "user-plus"), Map.entry("REFERRAL", "users"),
        Map.entry("WALK_IN", "footprints"), Map.entry("OTHER", "tag"), Map.entry(PHONE_CALL, "smartphone"),
        Map.entry(UNKNOWN, "circle-help"));

    private SourceCatalog() {
    }

    /** Manba ikonka kodi (lucide nomi); tanilmasa {@code tag}. */
    public static String icon(String source) {
        String key = normalize(source);
        return key == null ? ICONS.get(UNKNOWN) : ICONS.getOrDefault(key, "tag");
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
