package com.crm.audit;

import com.crm.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Har kuni 03:30 da eski audit yozuvlarini o'chiradi — ikki muddat (phase5-audit Q14):
 * <ul>
 *   <li>moliyaviy (to'lov, bekor qilish, qaytarish, oylik, kassa, balans, bonus/jarima,
 *       oylik qoidasi, chiqim/kirim) — {@code financial-retention-days} (365);</li>
 *   <li>qolganlari — {@code retention-days} (180).</li>
 * </ul>
 * Qaysi yozuv moliyaviy — {@link AuditProperties#getFinancialActions()} (amal bo'yicha) yoki
 * {@link AuditProperties#getFinancialEntityTypes()} (obyekt turi bo'yicha).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditRetentionJob {

    private final AuditLogRepository auditLogRepository;
    private final AuditProperties properties;

    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Tashkent")
    @Transactional
    public void purgeOldLogs() {
        purge(LocalDate.now());
    }

    /** {@code today} — testlar uchun; muddat 0 yoki manfiy bo'lsa o'sha toifa o'chirilmaydi. */
    @Transactional
    public void purge(LocalDate today) {
        List<String> actions = nonEmpty(properties.getFinancialActions());
        List<String> types = nonEmpty(properties.getFinancialEntityTypes());

        int regularDays = properties.getRetentionDays();
        if (regularDays > 0) {
            LocalDateTime cutoff = today.minusDays(regularDays).atStartOfDay();
            int removed = auditLogRepository.deleteRegularOlderThan(cutoff, actions, types);
            log.info("Audit tozalash: {} kundan eski {} oddiy yozuv o'chirildi (cutoff={})",
                regularDays, removed, cutoff);
        }

        int financialDays = properties.getFinancialRetentionDays();
        if (financialDays > 0) {
            LocalDateTime cutoff = today.minusDays(financialDays).atStartOfDay();
            int removed = auditLogRepository.deleteFinancialOlderThan(cutoff, actions, types);
            log.info("Audit tozalash: {} kundan eski {} moliyaviy yozuv o'chirildi (cutoff={})",
                financialDays, removed, cutoff);
        }
    }

    /** Bo'sh {@code IN ()} SQL da xato — ro'yxat bo'sh bo'lsa hech narsaga mos kelmaydigan qiymat. */
    private static List<String> nonEmpty(List<String> values) {
        return values == null || values.isEmpty() ? List.of("\u0000none") : values;
    }
}
