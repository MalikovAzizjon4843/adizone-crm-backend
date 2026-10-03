package com.crm.telegram;

import com.crm.exception.CodedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Mini App {@code Telegram.WebApp.initData} tekshiruvi (docs/design/telegram-platform.md §3.2,
 * core.telegram.org/bots/webapps "Validating data received via the Mini App"):
 * <pre>
 * data_check_string = hash dan boshqa barcha "key=value" (value URL-decode qilingan), kalit bo'yicha
 *                     alifbo tartibida, "\n" bilan ulangan
 * secret_key        = HMAC_SHA256(key = "WebAppData", message = bot_token)
 * hash              = hex(HMAC_SHA256(key = secret_key, message = data_check_string))
 * </pre>
 * {@code auth_date} — {@code now − auth_date ≤ telegram.app.init-data-max-age} (1 soat), kelajakda
 * 60 soniyadan ortiq bo'lsa ham rad.
 *
 * <p>initData, {@code hash} va bot tokeni hech qachon logga yozilmaydi.
 */
@Component
@RequiredArgsConstructor
public class TelegramInitDataValidator {

    static final String HMAC = "HmacSHA256";
    private static final long FUTURE_SKEW_SECONDS = 60;
    private static final int MAX_LENGTH = 8192;

    private final TelegramProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock billingClock;

    /** Tekshirilgan Telegram foydalanuvchisi (initData {@code user} maydoni). */
    public record WebAppUser(long id, String firstName, String lastName, String username, String languageCode) {
    }

    public WebAppUser validate(String initData) {
        if (!properties.hasBotToken()) {
            throw new CodedException(HttpStatus.SERVICE_UNAVAILABLE, "app.auth.notConfigured");
        }
        if (initData == null || initData.isBlank() || initData.length() > MAX_LENGTH) {
            throw invalid();
        }
        Map<String, String> fields = parse(initData);
        String hash = fields.remove("hash");
        if (hash == null || hash.isBlank()) {
            throw invalid();
        }
        String dataCheckString = fields.entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .collect(Collectors.joining("\n"));
        String expected = sign(properties.getBotToken(), dataCheckString);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                hash.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII))) {
            throw invalid();
        }

        long authDate;
        try {
            authDate = Long.parseLong(fields.getOrDefault("auth_date", ""));
        } catch (NumberFormatException e) {
            throw invalid();
        }
        long now = billingClock.instant().getEpochSecond();
        if (authDate > now + FUTURE_SKEW_SECONDS) {
            throw invalid();
        }
        if (now - authDate > properties.getApp().getInitDataMaxAge().getSeconds()) {
            throw new CodedException(HttpStatus.UNAUTHORIZED, "app.auth.initDataExpired");
        }
        return user(fields.get("user"));
    }

    /** Testlar va diagnostika uchun: shu bot tokeni bilan data_check_string imzosi (hex). */
    public static String sign(String botToken, String dataCheckString) {
        try {
            Mac secretMac = Mac.getInstance(HMAC);
            secretMac.init(new SecretKeySpec("WebAppData".getBytes(StandardCharsets.UTF_8), HMAC));
            byte[] secretKey = secretMac.doFinal(botToken.getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secretKey, HMAC));
            return HexFormat.of().formatHex(mac.doFinal(dataCheckString.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 mavjud emas", e);
        }
    }

    /** Kalitlar tartiblangan; takroriy kalit yoki "=" siz juftlik — yaroqsiz. */
    private static Map<String, String> parse(String initData) {
        Map<String, String> fields = new TreeMap<>();
        for (String pair : initData.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                throw invalid();
            }
            String key;
            String value;
            try {
                key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw invalid();
            }
            if (fields.put(key, value) != null) {
                throw invalid();
            }
        }
        return fields;
    }

    private WebAppUser user(String json) {
        if (json == null || json.isBlank()) {
            throw invalid();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.path("id").canConvertToLong() || node.path("id").asLong() <= 0) {
                throw invalid();
            }
            return new WebAppUser(node.path("id").asLong(),
                text(node, "first_name"), text(node, "last_name"),
                text(node, "username"), text(node, "language_code"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw invalid();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    private static CodedException invalid() {
        return new CodedException(HttpStatus.UNAUTHORIZED, "app.auth.invalidInitData");
    }
}
