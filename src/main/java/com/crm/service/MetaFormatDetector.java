package com.crm.service;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Meta forma javobidan o'qish formatini aniqlash (buyurtmachi 16.10.2026) — {@code MetaCrmField.FORMAT} savoli uchun.
 * Sof funksiya: javob matnida (katta-kichik harfsiz, apostrof turlari bir xil) ONLINE kalit so'zi bo'lsa — ONLINE,
 * OFFLINE kalit so'zi bo'lsa — OFFLINE; ikkalasi ham yoki hech biri bo'lmasa — null ("aniqlanmagan", taxmin qilinmaydi).
 * Kalit so'zlar forma sozlamasidan ({@code meta_lead_forms.format_*_keywords}, vergul bilan), bo'sh bo'lsa — standart.
 */
public final class MetaFormatDetector {

    public static final List<String> DEFAULT_ONLINE = List.of("onlayn", "online", "онлайн");
    public static final List<String> DEFAULT_OFFLINE = List.of("oflayn", "offline", "офлайн", "markaz", "o'quv markaz");

    private MetaFormatDetector() {
    }

    /** @return {@code "ONLINE"}, {@code "OFFLINE"} yoki null */
    public static String detect(String answer, String onlineKeywords, String offlineKeywords) {
        if (answer == null || answer.isBlank()) {
            return null;
        }
        String text = normalize(answer);
        boolean online = keywords(onlineKeywords, DEFAULT_ONLINE).stream().anyMatch(text::contains);
        boolean offline = keywords(offlineKeywords, DEFAULT_OFFLINE).stream().anyMatch(text::contains);
        if (online == offline) {
            return null;
        }
        return online ? "ONLINE" : "OFFLINE";
    }

    /** Vergul bilan ajratilgan ro'yxat; bo'sh — standart. */
    static List<String> keywords(String csv, List<String> defaults) {
        if (csv == null || csv.isBlank()) {
            return defaults;
        }
        List<String> parsed = Arrays.stream(csv.split(","))
            .map(MetaFormatDetector::normalize)
            .filter(s -> !s.isEmpty())
            .toList();
        return parsed.isEmpty() ? defaults : parsed;
    }

    /** Kichik harf, o' / o‘ / oʻ / o` — bitta apostrof, ortiqcha bo'shliqlar bitta. */
    static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT)
            .replace('‘', '\'').replace('’', '\'').replace('ʻ', '\'').replace('ʼ', '\'').replace('`', '\'')
            .replaceAll("\\s+", " ")
            .trim();
    }
}
