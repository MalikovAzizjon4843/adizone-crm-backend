package com.crm.telegram;

import com.crm.audit.AuditAction;
import com.crm.audit.Audited;
import com.crm.dto.response.ApiResponse;
import com.crm.exception.CodedException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Bot webhook'ini o'rnatish — faqat SUPER_ADMIN ({@code /api/admin/**}). URL va sir env'dan
 * ({@code TELEGRAM_WEBHOOK_URL}, {@code TELEGRAM_WEBHOOK_SECRET}); so'rov tanasi yo'q — sir hech
 * qachon javobga yoki logga chiqmaydi.
 */
@RestController
@RequestMapping("/api/admin/telegram")
@RequiredArgsConstructor
public class TelegramAdminController {

    /** Telegram {@code secret_token}: 1–256 belgi, {@code A-Z a-z 0-9 _ -}; biz kamida 32 talab qilamiz. */
    private static final Pattern SECRET = Pattern.compile("[A-Za-z0-9_-]{32,256}");
    static final List<String> ALLOWED_UPDATES = List.of("message");

    private final TelegramProperties properties;
    private final TelegramBotApi botApi;

    @PostMapping("/set-webhook")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Audited(action = AuditAction.UPDATE, entity = "Telegram", summary = "'Telegram webhook o''rnatildi'")
    public ApiResponse<Map<String, Object>> setWebhook() {
        if (!properties.hasBotToken()) {
            throw new CodedException(HttpStatus.SERVICE_UNAVAILABLE, "telegram.notConfigured");
        }
        String secret = properties.getWebhookSecret();
        if (secret == null || !SECRET.matcher(secret).matches()) {
            throw CodedException.badRequest("telegram.webhookSecret.invalid");
        }
        String url = properties.getWebhookUrl();
        if (url == null || !url.startsWith("https://")) {
            throw CodedException.badRequest("telegram.webhookUrl.invalid");
        }
        JsonNode response;
        try {
            response = botApi.setWebhook(url, secret, ALLOWED_UPDATES);
        } catch (IllegalStateException e) {
            throw new CodedException(HttpStatus.BAD_GATEWAY, "telegram.setWebhook.failed", e.getMessage());
        }
        if (!response.path("ok").asBoolean(false)) {
            throw new CodedException(HttpStatus.BAD_GATEWAY, "telegram.setWebhook.failed",
                response.path("description").asText("?"));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("url", url);
        data.put("allowedUpdates", ALLOWED_UPDATES);
        data.put("description", response.path("description").asText(null));
        return ApiResponse.success(data);
    }
}
