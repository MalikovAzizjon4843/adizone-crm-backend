package com.crm.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Graph API dan kelgan bitta lid — Jackson daraxtidan ajratilgan shakl.
 *
 * <p>Webhook oqimi ham, backfill ham AYNAN shu shaklga keltiriladi va
 * undan keyin bitta kod ishlaydi ({@link MetaLeadIngestService}). Ikkita
 * oqim ikkita nusxa mantiq degani bo'lardi va ular albatta bir-biridan
 * ajralib ketardi.
 *
 * <p>{@code fields} dagi kalitlar XOM: Meta ularni savol matnidan yasaydi
 * va ichida apostrof, {@code ?}, {@code /} bo'ladi. Ular
 * {@code meta_lead_form_questions.question_key} bilan AYNAN solishtiriladi.
 */
public record MetaLeadPayload(
    String leadgenId,
    String formId,
    String adId,
    /** {@code ig} | {@code fb} | null — manbani aniqlash uchun. */
    String platform,
    Instant createdTime,
    List<FieldValue> fields,
    /** Graph bergan xom JSON — {@code leads.meta_raw_json} ga tushadi. */
    String rawJson) {

    public record FieldValue(String key, List<String> values) {

        /** Birinchi (odatda yagona) javob, bo'sh bo'lsa null. */
        public String first() {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
            return null;
        }
    }

    /**
     * Graph javobini ajratadi.
     *
     * <p>{@code form_id} javobda bo'lmasligi mumkin —
     * {@code /{formId}/leads} ro'yxatida u ortiqcha, chunki forma
     * so'rovning o'zida ko'rsatilgan. Shuning uchun {@code fallbackFormId}
     * bor.
     */
    public static MetaLeadPayload from(JsonNode node, String fallbackFormId, String rawJson) {
        List<FieldValue> fields = new ArrayList<>();
        for (JsonNode entry : node.path("field_data")) {
            String key = entry.path("name").asText(null);
            if (key == null || key.isBlank()) {
                continue;
            }
            List<String> values = new ArrayList<>();
            for (JsonNode value : entry.path("values")) {
                values.add(value.asText(null));
            }
            fields.add(new FieldValue(key, values));
        }

        String formId = node.path("form_id").asText(null);
        if (formId == null || formId.isBlank()) {
            formId = fallbackFormId;
        }

        return new MetaLeadPayload(
            node.path("id").asText(null),
            formId,
            node.path("ad_id").asText(null),
            node.path("platform").asText(null),
            parseCreatedTime(node.path("created_time").asText(null)),
            fields,
            rawJson);
    }

    /**
     * {@code created_time} — ISO-8601 offset bilan
     * ({@code 2026-09-18T10:22:31+0000}).
     *
     * <p>Tanib bo'lmasa null qaytadi va lid {@code createdAt} i hozirgi
     * vaqt bo'lib qoladi. Sana tufayli lidni yo'qotish mumkin emas.
     */
    private static Instant parseCreatedTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (Exception ignored) {
            // "+0000" (ikki nuqtasiz) shakli — ISO parser uni qabul qilmaydi.
            try {
                return OffsetDateTime.parse(
                    raw.replaceAll("([+-]\\d{2})(\\d{2})$", "$1:$2")).toInstant();
            } catch (Exception e) {
                return null;
            }
        }
    }
}
