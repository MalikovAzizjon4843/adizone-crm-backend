package com.crm.billing.support;

import com.crm.telegram.TelegramBotApi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Testlarda {@link TelegramBotApi} o'rniga: Telegram'ga so'rov ketmaydi, chaqiruvlar yoziladi
 * (docs/design/telegram-platform.md §8 — "soxta TelegramClient, WireMock shart emas").
 */
public class RecordingTelegramBotApi implements TelegramBotApi {

    public record Sent(long chatId, String html, Map<String, Object> replyMarkup, boolean silent) {
    }

    public record WebhookCall(String url, String secretToken, List<String> allowedUpdates) {
    }

    public final List<Sent> sent = new CopyOnWriteArrayList<>();
    public final List<WebhookCall> webhookCalls = new CopyOnWriteArrayList<>();
    /** setWebhook javobi — test o'zgartirishi mumkin. */
    public volatile boolean webhookOk = true;
    /** sendMessage javobi — false: Telegram xatosi (outbox qayta urinishi testi). */
    public volatile boolean sendOk = true;

    public void reset() {
        sent.clear();
        webhookCalls.clear();
        webhookOk = true;
        sendOk = true;
    }

    @Override
    public boolean sendMessage(long chatId, String html, Map<String, Object> replyMarkup, boolean silent) {
        sent.add(new Sent(chatId, html, replyMarkup, silent));
        return sendOk;
    }

    @Override
    public JsonNode setWebhook(String url, String secretToken, List<String> allowedUpdates) {
        webhookCalls.add(new WebhookCall(url, secretToken, allowedUpdates));
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("ok", webhookOk);
        node.put("description", webhookOk ? "Webhook was set" : "Bad Request: bad webhook");
        return node;
    }
}
