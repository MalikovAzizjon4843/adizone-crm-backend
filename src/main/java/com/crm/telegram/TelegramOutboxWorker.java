package com.crm.telegram;

import com.crm.entity.TelegramOutbox;
import com.crm.repository.TelegramOutboxRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * {@code telegram_outbox} ni yuboradi (docs/design/telegram-platform.md §11.5). Bitta instans, {@code fixedDelay}
 * — bir vaqtda ikki aylanish yo'q. Yuborish tranzaksiyadan TASHQARIDA, natija alohida tranzaksiyada.
 * Xato: 1 / 5 / 30 / 120 daqiqadan keyin qayta, 5-urinishda FAILED. Telegram'da idempotentlik yo'q —
 * at-least-once.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TelegramOutboxWorker {

    private static final int BATCH = 50;
    private static final int MAX_ATTEMPTS = 5;
    private static final int[] BACKOFF_MINUTES = {1, 5, 30, 120};

    private final TelegramOutboxRepository repository;
    private final TelegramBotApi botApi;
    private final TelegramProperties properties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final Clock billingClock;

    @Value("${telegram.outbox.enabled:true}")
    private boolean enabled;

    @Scheduled(fixedDelayString = "${telegram.outbox.delay-ms:5000}", initialDelay = 10_000)
    public void tick() {
        if (!enabled || !properties.isEnabled() || !properties.hasBotToken()) {
            return;
        }
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.error("Telegram outbox aylanishi xatosi: {}", e.toString());
        }
    }

    /** Bitta aylanish; yuborilganlar soni. Testlar to'g'ridan-to'g'ri chaqiradi. */
    public int runOnce() {
        LocalDateTime now = LocalDateTime.now(billingClock);
        List<TelegramOutbox> due = tx.execute(s -> repository.findDue(now, PageRequest.ofSize(BATCH)));
        int sent = 0;
        for (TelegramOutbox row : due == null ? List.<TelegramOutbox>of() : due) {
            boolean ok = botApi.sendMessage(row.getChatId(), row.getText(), markup(row.getReplyMarkup()),
                Boolean.TRUE.equals(row.getSilent()));
            tx.executeWithoutResult(s -> repository.findById(row.getId()).ifPresent(r -> {
                LocalDateTime at = LocalDateTime.now(billingClock);
                int attempts = r.getAttempts() + 1;
                r.setAttempts(attempts);
                if (ok) {
                    r.setStatus(TelegramOutbox.Status.SENT);
                    r.setSentAt(at);
                    return;
                }
                r.setLastError("sendMessage=false");
                if (attempts >= MAX_ATTEMPTS) {
                    r.setStatus(TelegramOutbox.Status.FAILED);
                } else {
                    r.setNotBefore(at.plusMinutes(BACKOFF_MINUTES[Math.min(attempts - 1, BACKOFF_MINUTES.length - 1)]));
                }
            }));
            if (ok) {
                sent++;
            }
        }
        return sent;
    }

    private Map<String, Object> markup(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (Exception e) {
            return null;
        }
    }
}
