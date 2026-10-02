package com.crm.dashboard;

import com.crm.dashboard.DirectorDashboardService.Section;
import com.crm.entity.DirectorDailyStat;
import com.crm.repository.DirectorDailyStatRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Kunlik snapshot (director-dashboard §3.6, §7 #19): bo'lim xulosalari {@code director_daily_stats}
 * ga JSON bo'lib yoziladi.
 * <ul>
 *   <li>20:00 — {@code final = false} (Telegram uchun, {@link DirectorDigestService});</li>
 *   <li>23:55 — kun yakuni, {@code final = true};</li>
 *   <li>har kecha oxirgi 7 kun qayta hisoblanadi ({@code version++}) — kechikib kiritilgan
 *       to'lov/davomat. {@code debtors} bundan mustasno: "o'sha kungi holat" qayta tiklanmaydi.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DirectorSnapshotService {

    private final DirectorDashboardService dashboardService;
    private final DebtorMetricsService debtorMetrics;
    private final DirectorDailyStatRepository statRepository;
    private final DashboardProperties properties;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;
    private final Clock billingClock;

    /** Kun yakuni + oxirgi N kunni qayta hisoblash. */
    @Scheduled(cron = "${app.dashboard.snapshot.final-cron:0 55 23 * * *}", zone = "Asia/Tashkent")
    public void nightly() {
        if (!properties.getSnapshot().isEnabled()) {
            return;
        }
        LocalDate today = LocalDate.now(billingClock);
        snapshotDay(today, true);
        for (int i = 1; i <= properties.getSnapshot().getRecomputeDays(); i++) {
            try {
                snapshotDay(today.minusDays(i), true);
            } catch (RuntimeException e) {
                log.error("Dashboard snapshot {} qayta hisoblanmadi: {}", today.minusDays(i), e.getMessage(), e);
            }
        }
    }

    /**
     * Bitta kun — hamma bo'lim, default filtrlar, majburan jonli hisob. Debtors faqat o'z kunida
     * (bugun) yoziladi, qarzdor id lari bilan (ertangi {@code clearedToday} uchun).
     *
     * @return yozilgan bo'limlar (kalit → versiya)
     */
    public Map<String, Integer> snapshotDay(LocalDate day, boolean finalized) {
        LocalDateTime now = LocalDateTime.now(billingClock);
        DashboardPeriod p = DashboardPeriod.of("DAY", day, null, null, now);
        DirectorDashboardService.Summary s = dashboardService.computeLive(p,
            new DirectorDashboardService.Params("DAY", day, null, null, null, null, false),
            EnumSet.of(Section.FUNNEL, Section.COLLECTIONS, Section.ATTENDANCE, Section.TRIALS, Section.OPERATORS));
        Map<String, Object> payloads = new LinkedHashMap<>();
        payloads.put(Section.FUNNEL.key, s.funnel());
        payloads.put(Section.COLLECTIONS.key, s.collections());
        payloads.put(Section.ATTENDANCE.key, s.attendance());
        payloads.put(Section.TRIALS.key, s.trials());
        payloads.put(Section.OPERATORS.key, s.operators());
        if (day.equals(now.toLocalDate())) {
            payloads.put(Section.DEBTORS.key, debtorMetrics.live(day, false));
        }
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Map<String, Integer> written = new LinkedHashMap<>();
        tx.executeWithoutResult(st -> payloads.forEach((section, payload) -> {
            if (payload == null) {
                return;
            }
            DirectorDailyStat row = statRepository.findById(new DirectorDailyStat.Key(day, section))
                .orElseGet(() -> DirectorDailyStat.builder().statDate(day).section(section).version(0).build());
            row.setPayload(json(payload));
            row.setComputedAt(now);
            row.setFinalized(row.isFinalized() || finalized);
            row.setVersion(row.getVersion() + 1);
            statRepository.save(row);
            written.put(section, row.getVersion());
        }));
        dashboardService.invalidate();
        log.info("Dashboard snapshot {} (final={}): {}", day, finalized, written);
        return written;
    }

    private String json(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
