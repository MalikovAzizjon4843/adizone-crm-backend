package com.crm.telegram;

/**
 * Bot API {@code sendMessage} natijasi ({@code TelegramOutboxWorker} qaror qiladi):
 * <ul>
 *   <li>{@code ok} — yuborildi;</li>
 *   <li>{@code errorCode} — Telegram {@code error_code} (HTTP holati); 0 — tarmoq/javobni o'qish xatosi;</li>
 *   <li>{@code description} — Telegram {@code description} yoki xato matni (tokensiz);</li>
 *   <li>{@code retryAfter} — 429 da {@code parameters.retry_after} (soniya), aks holda null.</li>
 * </ul>
 */
public record SendResult(boolean ok, int errorCode, String description, Integer retryAfter) {

    public static final SendResult OK = new SendResult(true, 0, null, null);

    public static SendResult error(int errorCode, String description, Integer retryAfter) {
        return new SendResult(false, errorCode, description, retryAfter);
    }

    /** 403: bot bloklangan / foydalanuvchi o'chirilgan — qayta urinish befoyda, chat uziladi. */
    public boolean blocked() {
        return !ok && errorCode == 403;
    }

    /** 429: flood nazorati — {@link #retryAfter} soniyadan keyin. */
    public boolean rateLimited() {
        return !ok && errorCode == 429;
    }

    /** Boshqa 4xx (400 "chat not found", HTML xatosi …) — qayta urinilmaydi. */
    public boolean permanent() {
        return !ok && errorCode >= 400 && errorCode < 500 && errorCode != 429;
    }

    /** {@code last_error} uchun: {@code HTTP 403: Forbidden: bot was blocked by the user}. */
    public String describe() {
        String text = description == null || description.isBlank() ? "sendMessage=false" : description;
        return errorCode > 0 ? "HTTP " + errorCode + ": " + text : text;
    }
}
