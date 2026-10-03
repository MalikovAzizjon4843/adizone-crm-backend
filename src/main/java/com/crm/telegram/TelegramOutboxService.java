package com.crm.telegram;

import com.crm.entity.TelegramOutbox;
import com.crm.repository.TelegramOutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Bot xabarini navbatga qo'yish (docs/design/telegram-platform.md §11.5). Chaqiruvchining tranzaksiyasida
 * yoziladi — biznes amali rollback bo'lsa xabar ham yo'q.
 *
 * <p><b>Sokin soatlar</b> ({@link #QUIET_FROM}–{@link #QUIET_TO}, Asia/Tashkent): NORMAL — {@code not_before}
 * keyingi 08:00; HIGH — darhol, lekin ovozsiz.
 */
@Service
@RequiredArgsConstructor
public class TelegramOutboxService {

    public static final LocalTime QUIET_FROM = LocalTime.of(21, 0);
    public static final LocalTime QUIET_TO = LocalTime.of(8, 0);
    /** Chat push'i: bir suhbat bo'yicha shuncha daqiqada bitta xabar. */
    public static final int CHAT_WINDOW_MINUTES = 5;
    private static final int TEXT_MAX = 4000;

    private final TelegramOutboxRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock billingClock;

    /** @return true — yangi qator yozildi; false — {@code dedupeKey} band (takror). */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean enqueue(long chatId, String html, Map<String, Object> replyMarkup, TelegramOutbox.Priority priority,
                           String dedupeKey, String eventCode) {
        LocalDateTime now = LocalDateTime.now(billingClock);
        boolean quiet = isQuiet(now.toLocalTime());
        LocalDateTime notBefore = priority == TelegramOutbox.Priority.NORMAL && quiet ? quietEnd(now) : now;
        boolean silent = priority == TelegramOutbox.Priority.HIGH && quiet;
        String text = html.length() > TEXT_MAX ? html.substring(0, TEXT_MAX - 3) + "..." : html;
        return repository.insertIfAbsent(chatId, text, json(replyMarkup), silent, priority.name(), notBefore,
            dedupeKey, eventCode, now) > 0;
    }

    /**
     * Chat push'i uchun dedupe kaliti: oddiy vaqtda {@link #CHAT_WINDOW_MINUTES} daqiqalik oyna, sokin soatda
     * butun tun bitta (08:00 da bitta xabar).
     */
    public String windowKey(String prefix) {
        LocalDateTime now = LocalDateTime.now(billingClock);
        if (isQuiet(now.toLocalTime())) {
            return prefix + ":q:" + quietEnd(now).toLocalDate();
        }
        long minute = now.toEpochSecond(ZoneOffset.UTC) / 60;
        return prefix + ":" + (minute / CHAT_WINDOW_MINUTES);
    }

    public static boolean isQuiet(LocalTime t) {
        return !t.isBefore(QUIET_FROM) || t.isBefore(QUIET_TO);
    }

    /** Joriy sokin oynaning tugashi (bugun yoki ertaga 08:00). */
    static LocalDateTime quietEnd(LocalDateTime now) {
        return now.toLocalTime().isBefore(QUIET_TO)
            ? now.toLocalDate().atTime(QUIET_TO)
            : now.toLocalDate().plusDays(1).atTime(QUIET_TO);
    }

    /** Inline web_app tugmasi (Mini App'ni ochish); URL yo'q bo'lsa null. */
    public static Map<String, Object> openAppButton(String text, String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        return Map.of("inline_keyboard", List.of(List.of(Map.of("text", text, "web_app", Map.of("url", url)))));
    }

    private String json(Map<String, Object> markup) {
        if (markup == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(markup);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
