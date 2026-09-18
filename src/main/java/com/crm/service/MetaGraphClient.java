package com.crm.service;

import com.crm.config.MetaProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Meta Graph API ga boradigan yagona eshik.
 *
 * <p><b>Token Authorization sarlavhasida ketadi</b>, {@code ?access_token=}
 * da emas. Sabab bitta: query parametrdagi token proxy loglariga, xato
 * xabarlariga va stack trace larga tushadi. Sarlavhadagi token esa hech
 * qayerga oqmaydi.
 *
 * <p>Istisno — {@code paging.next}: uni Meta o'zi tuzadi va ichida token
 * bo'ladi. Shuning uchun har qanday URL logga faqat maskalangan holda
 * tushadi ({@link #maskUrl}).
 *
 * <p><b>Sahifa tokeni keshlanadi</b> ({@code AtomicReference}, 6 soat).
 * Har lid uchun {@code /me/accounts} ga borish 776 lidli backfill da 776 ta
 * ortiqcha so'rov degani. Token yaroqsiz bo'lsa (xato 190) kesh darhol
 * tozalanadi va keyingi chaqiruv uni qaytadan oladi.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MetaGraphClient {

    /** Bir sahifada nechta lid so'raymiz — Meta ning maksimumi ~100. */
    public static final int LEADS_PAGE_SIZE = 100;

    private static final String FORM_FIELDS = "id,name,status,locale,leads_count";
    private static final String QUESTION_FIELDS =
        "id,name,status,locale,questions{key,label,type,options}";
    private static final String LEAD_FIELDS =
        "id,created_time,form_id,ad_id,adset_id,campaign_id,platform,field_data";
    private static final String LEAD_LIST_FIELDS =
        "id,created_time,field_data,ad_id,platform";

    /** Sahifalash cheksiz aylanib qolmasin. */
    private static final int MAX_PAGES = 200;

    private final MetaProperties properties;
    private final ObjectMapper objectMapper;

    /** Keshlangan sahifa tokeni va uning muddati. */
    private final AtomicReference<CachedToken> pageToken = new AtomicReference<>();

    private record CachedToken(String value, Instant expiresAt) {
        boolean valid() {
            return value != null && !value.isBlank() && Instant.now().isBefore(expiresAt);
        }
    }

    private volatile RestClient restClient;

    private RestClient client() {
        RestClient local = restClient;
        if (local == null) {
            int timeoutMs = Math.max(properties.getRequestTimeoutSeconds(), 5) * 1000;
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(timeoutMs);
            factory.setReadTimeout(timeoutMs);
            local = RestClient.builder().requestFactory(factory).build();
            restClient = local;
        }
        return local;
    }

    // -- Token ------------------------------------------------------------

    /**
     * Sahifa tokeni — {@code /me/accounts} dan {@code pageId} ga mos
     * keluvchisi. Kesh muddati tugamagan bo'lsa so'rov yuborilmaydi.
     */
    public String getPageAccessToken() {
        CachedToken cached = pageToken.get();
        if (cached != null && cached.valid()) {
            return cached.value();
        }

        String systemToken = requireSystemToken();
        String pageId = requirePageId();

        URI uri = uri("/me/accounts",
            "fields", "id,name,access_token",
            "limit", "100");

        JsonNode body = get(uri, systemToken);
        for (JsonNode page : body.path("data")) {
            if (pageId.equals(page.path("id").asText())) {
                String token = page.path("access_token").asText(null);
                if (token == null || token.isBlank()) {
                    throw new MetaApiException(
                        "Sahifa topildi, lekin access_token berilmadi - System User ga "
                            + "sahifa huquqlari berilmagan bo'lishi mumkin", 0, 0);
                }
                int ttl = Math.max(properties.getPageTokenTtlHours(), 1);
                pageToken.set(new CachedToken(
                    token, Instant.now().plus(Duration.ofHours(ttl))));
                log.info("Meta: sahifa tokeni olindi (page={}, nom='{}')",
                    pageId, page.path("name").asText(""));
                return token;
            }
        }
        throw new MetaApiException(
            "Sahifa " + pageId + " System User ning /me/accounts ro'yxatida yo'q", 0, 0);
    }

    /** Xato 190 dan keyin chaqiriladi — keyingi so'rov tokenni qaytadan oladi. */
    public void invalidatePageToken() {
        if (pageToken.getAndSet(null) != null) {
            log.warn("Meta: sahifa tokeni keshi tozalandi");
        }
    }

    /** {@code GET /api/meta/status} uchun — Graph ga so'rov yubormasdan. */
    public boolean hasCachedPageToken() {
        CachedToken cached = pageToken.get();
        return cached != null && cached.valid();
    }

    // -- Formalar ---------------------------------------------------------

    /**
     * Sahifaning BARCHA leadgen formalari, ARCHIVED lar bilan birga.
     *
     * <p>Sahifalash majburiy: 25 dan ortiq formasi bor sahifada Meta
     * birinchi bo'lagini beradi va qolganini {@code paging.next} ortida
     * qoldiradi. Uni o'qimaslik - formalarning bir qismini jimgina
     * yo'qotish degani.
     */
    public List<JsonNode> getForms() {
        URI uri = uri("/" + requirePageId() + "/leadgen_forms",
            "fields", FORM_FIELDS,
            "limit", "100");
        return readAllPages(uri, getPageAccessToken());
    }

    /** Bitta formaning savollari va variantlari. */
    public JsonNode getFormQuestions(String formId) {
        URI uri = uri("/" + formId, "fields", QUESTION_FIELDS);
        return get(uri, getPageAccessToken());
    }

    // -- Lidlar -----------------------------------------------------------

    /** Bitta lid - webhook eventidan keyin shu chaqiriladi. */
    public JsonNode getLead(String leadgenId) {
        URI uri = uri("/" + leadgenId, "fields", LEAD_FIELDS);
        return get(uri, getPageAccessToken());
    }

    /**
     * Formaning lidlari, kursor bilan bitta sahifa.
     *
     * <p>Butun ro'yxatni bir chaqiruvda qaytarmaymiz: eng katta formada 776
     * lid bor va ularning hammasi xotirada turishi ham, bitta tranzaksiyada
     * yozilishi ham kerak emas. Backfill sahifama-sahifa yuradi.
     *
     * @param cursor oldingi javobdagi {@code paging.cursors.after}; birinchi
     *               sahifa uchun null
     */
    public LeadPage getFormLeads(String formId, String cursor) {
        URI uri = cursor != null && !cursor.isBlank()
            ? uri("/" + formId + "/leads",
                "fields", LEAD_LIST_FIELDS,
                "limit", String.valueOf(LEADS_PAGE_SIZE),
                "after", cursor)
            : uri("/" + formId + "/leads",
                "fields", LEAD_LIST_FIELDS,
                "limit", String.valueOf(LEADS_PAGE_SIZE));
        JsonNode body = get(uri, getPageAccessToken());

        List<JsonNode> rows = new ArrayList<>();
        body.path("data").forEach(rows::add);

        // paging.next yo'q bo'lsa - bu oxirgi sahifa. cursors.after esa
        // oxirgi sahifada ham keladi, faqat unga ergashish bo'sh javob beradi.
        boolean hasNext = !body.path("paging").path("next").asText("").isBlank();
        String next = hasNext
            ? body.path("paging").path("cursors").path("after").asText(null)
            : null;
        return new LeadPage(rows, next);
    }

    /** Bitta sahifa lidlar va keyingi kursor (yo'q bo'lsa null). */
    public record LeadPage(List<JsonNode> leads, String nextCursor) {
    }

    // -- Obuna ------------------------------------------------------------

    /** {@code POST /{pageId}/subscribed_apps} - leadgen obunasini yoqadi. */
    public JsonNode subscribePage() {
        URI uri = uri("/" + requirePageId() + "/subscribed_apps",
            "subscribed_fields", "leadgen");
        return post(uri, getPageAccessToken());
    }

    /** Joriy obuna holati. */
    public JsonNode getSubscriptions() {
        URI uri = uri("/" + requirePageId() + "/subscribed_apps");
        return get(uri, getPageAccessToken());
    }

    // -- Ichki ------------------------------------------------------------

    /**
     * {@code paging.next} bo'ylab oxirigacha yuradi.
     *
     * <p>{@code next} - Meta tuzgan to'liq URL va uning ichida token bor,
     * shuning uchun unga Authorization sarlavhasi QO'SHILMAYDI: ikkita token
     * ko'rgan Graph 400 qaytaradi.
     */
    private List<JsonNode> readAllPages(URI first, String token) {
        List<JsonNode> all = new ArrayList<>();
        URI uri = first;
        String bearer = token;
        int guard = 0;
        while (uri != null) {
            if (++guard > MAX_PAGES) {
                log.warn("Meta: sahifalash {} qadamdan oshdi, to'xtatildi ({})",
                    MAX_PAGES, maskUrl(uri));
                break;
            }
            JsonNode body = get(uri, bearer);
            body.path("data").forEach(all::add);

            String next = body.path("paging").path("next").asText(null);
            if (next == null || next.isBlank()) {
                uri = null;
            } else {
                uri = URI.create(next);
                bearer = null;
            }
        }
        return all;
    }

    /**
     * Graph URL ini yasaydi: {@code params} - kalit/qiymat juftliklari.
     *
     * <p>{@code UriComponentsBuilder} ATAYLAB ishlatilmaydi. Graph ning
     * {@code fields} qiymati jingalak qavs oladi
     * ({@code questions{key,label,type,options}}), Spring esa jingalak
     * qavsni URI shabloni o'zgaruvchisi deb o'qiydi va so'rov qurilishda
     * yiqiladi. Bu yerda hamma qiymat oddiygina foizli kodlanadi.
     */
    private URI uri(String path, String... params) {
        StringBuilder sb = new StringBuilder(properties.graphUrl(path));
        for (int i = 0; i + 1 < params.length; i += 2) {
            sb.append(i == 0 ? '?' : '&')
                .append(params[i])
                .append('=')
                .append(URLEncoder.encode(params[i + 1], StandardCharsets.UTF_8));
        }
        return URI.create(sb.toString());
    }

    private JsonNode get(URI uri, String token) {
        return exchange(false, uri, token);
    }

    private JsonNode post(URI uri, String token) {
        return exchange(true, uri, token);
    }

    private JsonNode exchange(boolean post, URI uri, String token) {
        log.debug("Meta {} {}", post ? "POST" : "GET", maskUrl(uri));
        String body;
        try {
            RestClient.RequestHeadersSpec<?> spec;
            if (post) {
                spec = client().post().uri(uri);
            } else {
                spec = client().get().uri(uri);
            }
            if (token != null && !token.isBlank()) {
                // Qaytgan qiymat e'tiborsiz qoldiriladi ataylab: RestClient
                // builderi o'zini qaytaradi va generiklar bilan qayta
                // o'zlashtirish faqat shovqin qo'shadi.
                spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            }
            body = spec.retrieve()
                // Xato tanasini O'ZIMIZ o'qiymiz: Meta 400 bilan birga
                // error.code beradi va aynan shu kod (190) token keshini
                // tozalash qaroriga asos. Default handler tanani yo'qotardi.
                .onStatus(status -> true, (request, response) -> { })
                .body(String.class);
        } catch (RestClientException e) {
            throw new MetaApiException(
                "Meta Graph API ga bora olmadi: " + e.getMessage(), e);
        }
        return parse(body, uri);
    }

    private JsonNode parse(String body, URI uri) {
        if (body == null || body.isBlank()) {
            throw new MetaApiException(
                "Meta bo'sh javob qaytardi (" + maskUrl(uri) + ")", 0, 0);
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(body);
        } catch (Exception e) {
            throw new MetaApiException("Meta javobi JSON emas: " + truncate(body, 200), e);
        }
        JsonNode error = node.get("error");
        if (error != null && !error.isNull()) {
            int code = error.path("code").asInt(0);
            String subcode = error.path("error_subcode").asText("");
            String message = error.path("message").asText("noma'lum xato");
            if (code == MetaApiException.CODE_INVALID_TOKEN) {
                invalidatePageToken();
            }
            throw new MetaApiException(
                "Meta xatosi " + code + (subcode.isBlank() ? "" : "/" + subcode)
                    + ": " + message,
                code, 0);
        }
        return node;
    }

    /**
     * URL ni logga yozish uchun xavfsiz shaklga keltiradi.
     *
     * <p>{@code access_token} maskalanadi - {@code paging.next} uni query
     * parametrda olib keladi va maskasiz log tokenni oshkor qilardi.
     * {@code after} kursori ham qisqartiriladi: u sir emas, lekin yuzlab
     * belgi va logni o'qib bo'lmas qilib qo'yadi.
     */
    static String maskUrl(URI uri) {
        String url = uri.toString();
        url = url.replaceAll("(?i)(access_token=)[^&]*", "$1***");
        url = url.replaceAll("(?i)(after=)[^&]{8,}", "$1***");
        return truncate(url, 300);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private String requireSystemToken() {
        String token = properties.getSystemUserToken();
        if (token == null || token.isBlank()) {
            throw new MetaApiException("meta.system-user-token sozlanmagan", 0, 0);
        }
        return token;
    }

    private String requirePageId() {
        String pageId = properties.getPageId();
        if (pageId == null || pageId.isBlank()) {
            throw new MetaApiException("meta.page-id sozlanmagan", 0, 0);
        }
        return pageId;
    }
}
