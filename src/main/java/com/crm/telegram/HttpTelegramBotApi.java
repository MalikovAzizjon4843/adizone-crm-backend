package com.crm.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link TelegramBotApi} — {@code https://api.telegram.org/bot<token>/<method>}.
 *
 * <p>Token URL ichida bo'lgani uchun xato matnida URL ham, token ham logga chiqmaydi — faqat
 * metod nomi va Telegram {@code description} i.
 */
@Component
@Slf4j
public class HttpTelegramBotApi implements TelegramBotApi {

    private final TelegramProperties properties;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public HttpTelegramBotApi(TelegramProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public boolean sendMessage(long chatId, String html, Map<String, Object> replyMarkup, boolean silent) {
        if (!properties.isEnabled() || !properties.hasBotToken()) {
            log.debug("Telegram o'chiq — bot javobi yuborilmadi");
            return false;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("text", html);
        body.put("parse_mode", "HTML");
        body.put("disable_web_page_preview", true);
        if (silent) {
            body.put("disable_notification", true);
        }
        if (replyMarkup != null) {
            body.put("reply_markup", replyMarkup);
        }
        try {
            JsonNode response = call("sendMessage", body);
            return response.path("ok").asBoolean(false);
        } catch (RuntimeException e) {
            log.warn("Telegram sendMessage xatosi: {}", safe(e));
            return false;
        }
    }

    @Override
    public JsonNode setWebhook(String url, String secretToken, List<String> allowedUpdates) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("url", url);
        body.put("secret_token", secretToken);
        body.put("allowed_updates", allowedUpdates);
        try {
            return call("setWebhook", body);
        } catch (RuntimeException e) {
            throw new IllegalStateException("setWebhook: " + safe(e));
        }
    }

    private JsonNode call(String method, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String url = properties.getApiBaseUrl() + "/bot" + properties.getBotToken() + "/" + method;
        try {
            String raw = restTemplate.postForObject(url, new HttpEntity<>(body, headers), String.class);
            return objectMapper.readTree(raw == null ? "{}" : raw);
        } catch (RestClientResponseException e) {
            // 4xx/5xx — Telegram tanasi {"ok":false,"description":...}; URL (token) emas, faqat tana
            try {
                return objectMapper.readTree(e.getResponseBodyAsString());
            } catch (Exception parse) {
                throw new IllegalStateException(method + " HTTP " + e.getStatusCode().value());
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(method + ": javobni o'qib bo'lmadi");
        }
    }

    /** Xato matni tokensiz: RestTemplate xabarlari URL ni (ya'ni tokenni) o'z ichiga olishi mumkin. */
    private String safe(RuntimeException e) {
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        String token = properties.getBotToken();
        return token != null && !token.isBlank() ? msg.replace(token, "***") : msg;
    }
}
