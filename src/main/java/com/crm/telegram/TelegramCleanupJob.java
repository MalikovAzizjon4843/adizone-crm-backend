package com.crm.telegram;

import com.crm.repository.AppLinkAttemptRepository;
import com.crm.repository.TelegramUpdateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Saqlash muddatlari (docs/design/telegram-platform.md §1, §6): {@code telegram_updates} — 7 kun,
 * {@code app_link_attempts} — 90 kun. Har kecha 03:45.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TelegramCleanupJob {

    private final TelegramUpdateRepository updateRepository;
    private final AppLinkAttemptRepository attemptRepository;
    private final Clock billingClock;

    @Scheduled(cron = "0 45 3 * * *", zone = "Asia/Tashkent")
    @Transactional
    public void cleanup() {
        LocalDateTime now = LocalDateTime.now(billingClock);
        int updates = updateRepository.deleteOlderThan(now.minusDays(7));
        int attempts = attemptRepository.deleteOlderThan(now.minusDays(90));
        if (updates > 0 || attempts > 0) {
            log.info("Telegram tozalash: {} update, {} bog'lash urinishi", updates, attempts);
        }
    }
}
