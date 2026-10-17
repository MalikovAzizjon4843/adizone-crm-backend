package com.crm.telegram;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Bot API'ning shu bosqichda kerakli qismi (docs/design/telegram-platform.md §1 — {@code TelegramClient}).
 * Interfeys: testlarda yozib oluvchi soxta bean bilan almashtiriladi, Telegram'ga so'rov ketmaydi.
 */
public interface TelegramBotApi {

    /** Ovozli xabar — {@link #sendMessage(long, String, Map, boolean)} ning {@code silent = false} varianti. */
    default boolean sendMessage(long chatId, String html, Map<String, Object> replyMarkup) {
        return sendMessage(chatId, html, replyMarkup, false);
    }

    /**
     * {@code sendMessage} ({@code parse_mode=HTML}); {@code silent} — {@code disable_notification}
     * (sokin soatlar, §11.5). Xatoni yutadi va {@code false} qaytaradi — bot javobi yetmasa ham webhook
     * 200 qaytishi kerak (aks holda Telegram update'ni qayta yuboradi).
     */
    boolean sendMessage(long chatId, String html, Map<String, Object> replyMarkup, boolean silent);

    /**
     * {@link #sendMessage(long, String, Map, boolean)} — Telegram xatosi tafsiloti bilan (outbox: 403 → chat uziladi,
     * 429 → {@code retry_after}, javob {@code last_error} ga). Standart: faqat ok/xato, kodsiz.
     */
    default SendResult send(long chatId, String html, Map<String, Object> replyMarkup, boolean silent) {
        return sendMessage(chatId, html, replyMarkup, silent) ? SendResult.OK : SendResult.error(0, null, null);
    }

    /**
     * {@code setWebhook}. Telegram javobi ({@code {"ok":..,"description":..}}) qaytadi; tarmoq xatosi —
     * {@link IllegalStateException}.
     */
    JsonNode setWebhook(String url, String secretToken, List<String> allowedUpdates);
}
