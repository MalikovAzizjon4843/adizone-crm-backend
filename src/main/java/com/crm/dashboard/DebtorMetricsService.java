package com.crm.dashboard;

import com.crm.billing.BillingProperties;
import com.crm.billing.DebtorService;
import com.crm.billing.Money;
import com.crm.dashboard.DirectorDtos.DebtorsSection;
import com.crm.dto.response.DebtorsListResponse;
import com.crm.entity.DirectorDailyStat;
import com.crm.repository.DirectorDailyStatRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Qarzdorlar (director-dashboard §1.3) — billing-v2 YAGONA ta'rifi ({@link DebtorService}),
 * yangi formula yo'q. Bugun — jonli; o'tgan kun — faqat {@code director_daily_stats} snapshot'i
 * (bo'lmasa {@code source = NONE}, qiymatlar null).
 */
@Service
@RequiredArgsConstructor
public class DebtorMetricsService {

    public static final String SECTION = "debtors";

    private final DebtorService debtorService;
    private final BillingProperties billingProperties;
    private final DirectorDailyStatRepository statRepository;
    private final ObjectMapper objectMapper;

    /** Davr oxiridagi holat: bugun kirsa — jonli, aks holda davr oxirgi kunining snapshot'i. */
    @Transactional(readOnly = true)
    public DebtorsSection summary(DashboardPeriod p) {
        if (p.includesToday() || p.to().isAfter(p.today())) {
            return live(p.today(), true);
        }
        DebtorsSection snap = snapshot(p.to());
        return snap != null ? snap.publicView()
            : new DebtorsSection(null, null, null, null, null, null, "NONE", null);
    }

    /**
     * Jonli hisob (snapshot yozishda ham shu). {@code withIds} — snapshot uchun qarzdor o'quvchi
     * id lari saqlanadi (ertangi {@code clearedToday} uchun); API javobida ular olib tashlanadi.
     */
    @Transactional(readOnly = true)
    public DebtorsSection live(LocalDate today, boolean publicView) {
        DebtorsListResponse list = debtorService.debtors(DebtorService.Filter.defaults(), today);
        DebtorService.Summary all = debtorService.debtorSummary(
            new DebtorService.Filter(DebtorService.Scope.ALL, null, null, null, null), today);
        List<Long> ids = list.getStudents().stream().map(DebtorsListResponse.DebtorStudent::getStudentId).toList();
        // Qarzdor bo'lgan kun: today − debtSince = grace (R1, billing-v2 §14.1)
        int newDay = billingProperties.getGraceDays();
        long newToday = list.getStudents().stream().filter(s -> s.getDaysOverdue() == newDay).count();
        DebtorsSection yesterday = snapshot(today.minusDays(1));
        Long cleared = null;
        if (yesterday != null && yesterday.studentIds() != null) {
            Set<Long> now = new HashSet<>(ids);
            cleared = yesterday.studentIds().stream().filter(id -> !now.contains(id)).count();
        }
        DebtorsSection s = new DebtorsSection(list.getTotalDebtors(), Money.normalize(Money.nz(list.getTotalDebt())),
            list.getOverdue7Plus(), newToday, cleared, Money.normalize(Money.nz(all.closedDebt())), "LIVE", ids);
        return publicView ? s.publicView() : s;
    }

    private DebtorsSection snapshot(LocalDate day) {
        return statRepository.findById(new DirectorDailyStat.Key(day, SECTION))
            .map(st -> {
                try {
                    DebtorsSection d = objectMapper.readValue(st.getPayload(), DebtorsSection.class);
                    return new DebtorsSection(d.count(), d.amount(), d.overdue7Plus(), d.newToday(), d.clearedToday(),
                        d.closedDebt(), "SNAPSHOT", d.studentIds());
                } catch (Exception e) {
                    return null;
                }
            }).orElse(null);
    }

}
