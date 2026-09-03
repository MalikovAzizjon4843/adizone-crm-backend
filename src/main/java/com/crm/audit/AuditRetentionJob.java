package com.crm.audit;

import com.crm.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Har kuni 03:30 da eski audit yozuvlarini o'chiradi. */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditRetentionJob {

    private final AuditLogRepository auditLogRepository;
    private final AuditProperties properties;

    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void purgeOldLogs() {
        int days = properties.getRetentionDays();
        if (days <= 0) {
            log.debug("Audit retention o'chirilgan (retention-days={})", days);
            return;
        }
        LocalDateTime cutoff = LocalDate.now().minusDays(days).atStartOfDay();
        int removed = auditLogRepository.deleteOlderThan(cutoff);
        log.info("Audit tozalash: {} kundan eski {} yozuv o'chirildi (cutoff={})",
            days, removed, cutoff);
    }
}
