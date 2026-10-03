package com.crm.telegram;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Bot API'ning shu bosqichda kerakli qismi (docs/design/telegram-platform.md §1 — {@code TelegramClient}).
 * Interfeys: testlarda yozib oluvchi soxta bean bilan almashtiriladi, Telegram'ga so'rov ketmaydi.
 */
public interface TelegramBotApi {

    /**
     * {@code sendMessage} ({@code parse_mode=HTML}). Xatoni yutadi va {@code false} qaytaradi — bot
     * javobi yetmasa ham webhook 200 qaytishi kerak (aks holda Telegram update'ni qayta yuboradi).
     */
    boolean sendMessage(long chatId, String html, Map<String, Object> replyMarkup);

    /**
     * {@code setWebhook}. Telegram javobi ({@code {"ok":..,"description":..}}) qaytadi; tarmoq xatosi —
     * {@link IllegalStateException}.
     */
    JsonNode setWebhook(String url, String secretToken, List<String> allowedUpdates);
}
