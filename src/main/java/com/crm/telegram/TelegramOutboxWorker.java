package com.crm.telegram;

import com.crm.entity.AppIdentity;
import com.crm.entity.TelegramOutbox;
import com.crm.miniapp.MiniAppLinkService;
import com.crm.repository.AppIdentityRepository;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code telegram_outbox} ni yuboradi (docs/design/telegram-platform.md §11.5, docs/design/notifications.md §5).
 * Bitta instans, {@code fixedDelay} — bir vaqtda ikki aylanish yo'q. Yuborish tranzaksiyadan TASHQARIDA, natija
 * alohida tranzaksiyada. Telegram'da idempotentlik yo'q — at-least-once.
 *
 * <p>Natija bo'yicha ({@link SendResult}), {@code last_error} — Telegram javobi (tokensiz):
 * <ul>
 *   <li><b>403</b> (bot bloklangan) — FAILED, qayta urinilmaydi; shu chatning qolgan PENDING xabarlari ham FAILED,
 *       chatning faol {@code AppIdentity} lari uziladi ({@code BOT_BLOCKED}) — /start + kontakt bilan qayta ulanadi;</li>
 *   <li><b>429</b> (flood nazorati) — urinish sanalmaydi, qator {@code retry_after} soniyadan keyin; butun worker
 *       shu muddatgacha to'xtaydi (aylanishning qolgani ham kutadi);</li>
 *   <li><b>boshqa 4xx</b> (400 "chat not found", HTML xatosi) — FAILED, qayta urinilmaydi;</li>
 *   <li><b>5xx / tarmoq</b> — 1 / 5 / 30 / 120 daqiqadan keyin qayta, 5-urinishda FAILED.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TelegramOutboxWorker {

    private static final int BATCH = 50;
    private static final int MAX_ATTEMPTS = 5;
    private static final int[] BACKOFF_MINUTES = {1, 5, 30, 120};
    /** 429 javobida {@code retry_after} bo'lmasa. */
    static final int DEFAULT_RETRY_AFTER_SECONDS = 30;
    private static final int ERROR_MAX = 500;

    private final TelegramOutboxRepository repository;
    private final TelegramBotApi botApi;
    private final TelegramProperties properties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final Clock billingClock;
    private final AppIdentityRepository identityRepository;
    private final MiniAppLinkService linkService;

    @Value(value = "${telegram.outbox.enabled:true}")
    private boolean enabled;

    /** 429: shu vaqtgacha hech narsa yuborilmaydi (null — to'xtash yo'q). */
    private volatile LocalDateTime pausedUntil;

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
        if (pausedUntil != null && now.isBefore(pausedUntil)) {
            return 0;
        }
        List<TelegramOutbox> due = tx.execute(s -> repository.findDue(now, PageRequest.ofSize(BATCH)));
        int sent = 0;
        // 403 bo'lgan chat — shu aylanishda olingan qolgan qatorlari ham yuborilmaydi (bazada allaqachon FAILED)
        Set<Long> blocked = new HashSet<>();
        for (TelegramOutbox row : due == null ? List.<TelegramOutbox>of() : due) {
            if (blocked.contains(row.getChatId())) {
                continue;
            }
            SendResult result = botApi.send(row.getChatId(), row.getText(), markup(row.getReplyMarkup()),
                Boolean.TRUE.equals(row.getSilent()));
            if (result.ok()) {
                sent++;
            }
            boolean stop = Boolean.TRUE.equals(tx.execute(s -> apply(row.getId(), result)));
            if (result.blocked()) {
                blocked.add(row.getChatId());
            }
            if (stop) {
                break;
            }
        }
        return sent;
    }

    /** 429 to'xtashini bekor qilish (testlar; soat orqaga qaytganda). */
    public void clearPause() {
        pausedUntil = null;
    }

    /** Natijani yozadi; true — aylanishni to'xtatish (429). */
    private boolean apply(Long id, SendResult result) {
        TelegramOutbox r = repository.findById(id).orElse(null);
        if (r == null || r.getStatus() != TelegramOutbox.Status.PENDING) {
            return false;
        }
        LocalDateTime at = LocalDateTime.now(billingClock);
        if (result.ok()) {
            r.setAttempts(r.getAttempts() + 1);
            r.setStatus(TelegramOutbox.Status.SENT);
            r.setSentAt(at);
            return false;
        }
        r.setLastError(cut(result.describe()));
        if (result.rateLimited()) {
            int seconds = result.retryAfter() != null && result.retryAfter() > 0
                ? result.retryAfter() : DEFAULT_RETRY_AFTER_SECONDS;
            LocalDateTime until = at.plusSeconds(seconds);
            r.setNotBefore(until);
            pausedUntil = until;
            log.warn("Telegram 429: outbox {} soniya to'xtaydi", seconds);
            return true;
        }
        int attempts = r.getAttempts() + 1;
        r.setAttempts(attempts);
        if (result.blocked()) {
            r.setStatus(TelegramOutbox.Status.FAILED);
            repository.save(r);
            repository.flush();
            onBlocked(r.getChatId(), r.getLastError());
            return false;
        }
        if (result.permanent() || attempts >= MAX_ATTEMPTS) {
            r.setStatus(TelegramOutbox.Status.FAILED);
        } else {
            r.setNotBefore(at.plusMinutes(BACKOFF_MINUTES[Math.min(attempts - 1, BACKOFF_MINUTES.length - 1)]));
        }
        return false;
    }

    /** 403: chatning qolgan xabarlari yuborilmaydi, faol identity'lar uziladi. */
    private void onBlocked(Long chatId, String error) {
        int failed = repository.failPendingForChat(chatId, cut("chat bloklangan: " + error),
            TelegramOutbox.Status.PENDING, TelegramOutbox.Status.FAILED);
        List<AppIdentity> identities = identityRepository.findByChatIdAndStatus(chatId, AppIdentity.Status.ACTIVE);
        identities.forEach(i -> linkService.unlink(i, "BOT_BLOCKED"));
        log.warn("Telegram 403: chat bloklangan — {} ta navbatdagi xabar bekor, {} ta identity uzildi",
            failed, identities.size());
    }

    private static String cut(String s) {
        return s == null || s.length() <= ERROR_MAX ? s : s.substring(0, ERROR_MAX - 3) + "...";
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
