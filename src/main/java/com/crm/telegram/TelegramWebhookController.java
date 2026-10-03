package com.crm.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Telegram webhook (docs/design/telegram-platform.md §1). SecurityConfig'da ochiq — himoya
 * {@code X-Telegram-Bot-Api-Secret-Token} sarlavhasi ({@code TELEGRAM_WEBHOOK_SECRET}, setWebhook
 * da beriladi). Sir sozlanmagan bo'lsa ham 401: webhook himoyasiz ishlamaydi.
 *
 * <p>Sarlavha to'g'ri bo'lsa har doim 200 qaytadi — 5xx Telegram'ni update'ni qayta yuborishga
 * majbur qiladi; takror esa {@code update_id} bo'yicha baribir tashlanadi.
 */
@RestController
@RequestMapping("/api/telegram/webhook")
@RequiredArgsConstructor
@Slf4j
public class TelegramWebhookController {

    static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";

    private final TelegramProperties properties;
    private final TelegramUpdateHandler handler;
    private final TelegramBotApi botApi;
    private final ObjectMapper objectMapper;

    @PostMapping
    public ResponseEntity<Void> receive(
            @RequestHeader(name = SECRET_HEADER, required = false) String secret,
            @RequestBody(required = false) byte[] body) {
        if (!secretValid(secret)) {
            log.warn("Telegram webhook: secret sarlavhasi mos kelmadi yoki yo'q");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<TelegramUpdateHandler.Outgoing> replies;
        try {
            JsonNode update = objectMapper.readTree(body != null ? body : new byte[0]);
            if (update == null || !update.path("update_id").canConvertToLong()) {
                return ResponseEntity.ok().build();
            }
            long updateId = update.path("update_id").asLong();
            if (!handler.register(updateId, kind(update))) {
                log.debug("Telegram webhook: takroriy update {}", updateId);
                return ResponseEntity.ok().build();
            }
            replies = handler.handle(update);
        } catch (Exception e) {
            log.error("Telegram webhook: update ishlanmadi: {}", e.toString());
            return ResponseEntity.ok().build();
        }
        // Commit'dan keyin: bot javobi yetmasa ham bog'lash saqlanib qoladi
        for (TelegramUpdateHandler.Outgoing reply : replies) {
            botApi.sendMessage(reply.chatId(), reply.html(), reply.replyMarkup());
        }
        return ResponseEntity.ok().build();
    }

    private boolean secretValid(String header) {
        String expected = properties.getWebhookSecret();
        if (expected == null || expected.isBlank() || header == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
            header.getBytes(StandardCharsets.UTF_8));
    }

    private static String kind(JsonNode update) {
        JsonNode message = update.path("message");
        if (message.isMissingNode()) {
            return firstOtherField(update);
        }
        if (message.has("contact")) {
            return "contact";
        }
        return message.has("text") ? "text" : "message";
    }

    private static String firstOtherField(JsonNode update) {
        var it = update.fieldNames();
        while (it.hasNext()) {
            String f = it.next();
            if (!"update_id".equals(f)) {
                return f.length() > 30 ? f.substring(0, 30) : f;
            }
        }
        return "unknown";
    }
}
