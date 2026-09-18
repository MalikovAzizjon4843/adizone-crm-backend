package com.crm.controller;

import com.crm.config.MetaProperties;
import com.crm.entity.MetaWebhookEvent;
import com.crm.service.MetaEventWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * Meta leadgen webhook i. Autentifikatsiyasiz ochiq
 * ({@code SecurityConfig}) — Meta bizning JWT imizni bilmaydi. Uning
 * o'rniga har so'rov {@code X-Hub-Signature-256} imzosi bilan
 * tekshiriladi.
 *
 * <p>Tekshiruvni {@code meta.verify-signature: false} bilan o'chirish
 * mumkin — {@code app-secret} hali olinmagan paytda sync va backfill ni
 * sinash uchun. O'chirilgan holatda endpoint HIMOYASIZ qoladi, shuning
 * uchun har so'rovda log ga ogohlantirish tushadi.
 *
 * <p><b>Bu yerda hech qanday og'ir ish qilinmaydi.</b> Meta javobni 20
 * soniya kutadi; kechikkan yetkazib berishdan keyin u qayta urinadi, bir
 * necha marta kechiksa esa obunani butunlay o'chirib qo'yadi. Shuning
 * uchun Graph API so'rovi ham, lid yaratish ham bu yerda emas —
 * {@code MetaLeadProcessingService} siklida.
 */
@RestController
@RequestMapping("/api/meta/webhook")
@RequiredArgsConstructor
@Slf4j
public class MetaWebhookController {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String SIGNATURE_PREFIX = "sha256=";

    /**
     * Imzo tekshirilmagan har bir so'rovda yoziladi. Matn ataylab keskin:
     * uni log da ko'rgan odam nima bo'layotganini darhol tushunishi kerak.
     */
    private static final String DISABLED_WARNING =
        "Meta webhook imzo tekshiruvi O'CHIRILGAN — faqat test uchun";

    private final MetaProperties properties;
    private final MetaEventWorker eventWorker;
    private final ObjectMapper objectMapper;

    /**
     * Meta ning verifikatsiya qo'l berishi.
     *
     * <p>Javob <b>toza matn</b> bo'lishi shart: Meta {@code hub.challenge}
     * ni AYNAN qaytarishni kutadi. JSON ga o'rasak (yoki qo'shtirnoq bilan
     * bersak) obuna o'rnatilmaydi.
     */
    @GetMapping
    public ResponseEntity<String> verify(
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String verifyToken,
            @RequestParam(name = "hub.challenge", required = false) String challenge) {

        String expected = properties.getVerifyToken();
        boolean ok = "subscribe".equals(mode)
            && expected != null && !expected.isBlank()
            && expected.equals(verifyToken);

        if (!ok) {
            log.warn("Meta webhook verifikatsiyasi rad etildi (mode={}, token mos kelmadi)",
                mode);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        log.info("Meta webhook verifikatsiyasi muvaffaqiyatli");
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_PLAIN)
            .body(challenge != null ? challenge : "");
    }

    /**
     * Leadgen xabarlari.
     *
     * <p>Tana {@code byte[]} sifatida olinadi, {@code String} emas: imzo
     * XOM baytlar ustidan hisoblangan. Matnga aylantirib qaytadan
     * baytlarga o'tkazish kodlash farqi tufayli imzoni buzishi mumkin va
     * bunday xato faqat kirill/apostrofli lidlarda chiqadi, ya'ni test
     * paytida ko'rinmaydi.
     */
    @PostMapping
    public ResponseEntity<String> receive(
            @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody(required = false) byte[] body) {

        byte[] payload = body != null ? body : new byte[0];

        if (!signatureAccepted(signature, payload)) {
            log.warn("Meta webhook: imzo mos kelmadi yoki yo'q (uzunlik={} bayt)",
                payload.length);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("invalid signature");
        }

        try {
            store(new String(payload, StandardCharsets.UTF_8));
        } catch (Exception e) {
            // Nima bo'lganda ham 200 qaytaramiz: 5xx Meta ni qayta urinishga
            // majbur qiladi va bir necha marta takrorlansa obuna o'chadi.
            // Yo'qolgan xabarni log dan topib, qo'lda backfill qilish
            // mumkin; o'chgan obunani esa hech kim sezmay qoladi.
            log.error("Meta webhook: xabar saqlanmadi", e);
        }
        return ResponseEntity.ok("EVENT_RECEIVED");
    }

    /** {@code entry[].changes[]} ichidan leadgen qiymatlarini ajratib yozadi. */
    private void store(String rawBody) throws Exception {
        JsonNode root = objectMapper.readTree(rawBody);
        int saved = 0;
        int duplicates = 0;

        for (JsonNode entry : root.path("entry")) {
            for (JsonNode change : entry.path("changes")) {
                if (!"leadgen".equals(change.path("field").asText(""))) {
                    continue;
                }
                JsonNode value = change.path("value");
                String leadgenId = value.path("leadgen_id").asText(null);
                if (leadgenId == null || leadgenId.isBlank()) {
                    log.warn("Meta webhook: leadgen_id yo'q, o'tkazib yuborildi");
                    continue;
                }

                MetaWebhookEvent event = MetaWebhookEvent.builder()
                    .leadgenId(leadgenId)
                    .formId(value.path("form_id").asText(null))
                    .pageId(value.path("page_id").asText(null))
                    // Meta bu maydonni "adgroup_id" deb yuboradi, Graph API
                    // esa lidda "ad_id" deydi — bitta narsaning ikki nomi.
                    .adId(value.path("adgroup_id").asText(null))
                    .createdTimeMs(value.path("created_time").isMissingNode()
                        ? null
                        : value.path("created_time").asLong() * 1000L)
                    .rawPayload(rawBody)
                    .status(MetaWebhookEvent.STATUS_PENDING)
                    .attempts(0)
                    .receivedAt(Instant.now())
                    .build();

                if (eventWorker.saveIfNew(event)) {
                    saved++;
                } else {
                    duplicates++;
                }
            }
        }

        if (saved > 0 || duplicates > 0) {
            log.info("Meta webhook: {} yangi event, {} takror", saved, duplicates);
        }
    }

    /**
     * So'rov qabul qilinadimi: imzo to'g'rimi YOKI tekshiruv o'chirilganmi.
     *
     * <p>Ikkita "o'chirilgan" holat bor va ikkalasi ham bir xil oqibatga
     * olib keladi — endpoint himoyasiz qoladi:
     * <ul>
     *   <li>{@code meta.verify-signature: false} — ataylab o'chirilgan;</li>
     *   <li>{@code meta.app-secret} bo'sh — tekshirishga sir yo'q.</li>
     * </ul>
     *
     * <p>Har ikkalasida ham HAR SO'ROVDA {@code WARN} yoziladi, bir marta
     * emas. "Vaqtincha o'chirib turamiz" degan sozlama aynan shunday
     * unutilib qoladi; loglardagi takroriy ogohlantirish esa ko'zga
     * tashlanadi.
     */
    private boolean signatureAccepted(String header, byte[] body) {
        if (!properties.isVerifySignature()) {
            log.warn(DISABLED_WARNING + " (meta.verify-signature=false)");
            return true;
        }
        String secret = properties.getAppSecret();
        if (secret == null || secret.isBlank()) {
            log.warn(DISABLED_WARNING + " (meta.app-secret bo'sh)");
            return true;
        }
        return signatureValid(header, secret, body);
    }

    /**
     * {@code X-Hub-Signature-256} ning haqiqiy tekshiruvi.
     *
     * <p>{@link MessageDigest#isEqual} ishlatiladi, {@code String.equals}
     * emas: oddiy taqqoslash birinchi farqda to'xtaydi va javob vaqtidan
     * imzoni bayt-bayt topib olish mumkin (timing attack).
     */
    private boolean signatureValid(String header, String secret, byte[] body) {
        if (header == null || !header.startsWith(SIGNATURE_PREFIX)) {
            return false;
        }

        byte[] expected;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            expected = mac.doFinal(body);
        } catch (Exception e) {
            log.error("Meta webhook: HMAC hisoblanmadi: {}", e.getMessage());
            return false;
        }

        byte[] received = decodeHex(header.substring(SIGNATURE_PREFIX.length()));
        return received != null && MessageDigest.isEqual(expected, received);
    }

    /** Hex matnni baytlarga — noto'g'ri shaklda null. */
    private static byte[] decodeHex(String hex) {
        if (hex == null || hex.length() % 2 != 0 || hex.isEmpty()) {
            return null;
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i] = (byte) ((hi << 4) + lo);
        }
        return out;
    }
}
