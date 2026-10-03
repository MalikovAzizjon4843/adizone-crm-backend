package com.crm.notification;

import com.crm.repository.UserNotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/** Xodim bildirishnomalari {@value #RETENTION_DAYS} kun saqlanadi (o'qilgan-o'qilmaganidan qat'i nazar). Har kecha 04:00. */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserNotificationRetentionJob {

    static final int RETENTION_DAYS = 90;

    private final UserNotificationRepository repository;
    private final Clock billingClock;

    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Tashkent")
    @Transactional
    public int cleanup() {
        int deleted = repository.deleteOlderThan(LocalDateTime.now(billingClock).minusDays(RETENTION_DAYS));
        if (deleted > 0) {
            log.info("Bildirishnomalar tozalandi: {} ta ({} kundan eski)", deleted, RETENTION_DAYS);
        }
        return deleted;
    }
}
