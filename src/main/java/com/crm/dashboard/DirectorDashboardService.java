package com.crm.dashboard;

import com.crm.billing.BillingAuth;
import com.crm.dashboard.DirectorDtos.AttendanceSection;
import com.crm.dashboard.DirectorDtos.CollectionsSection;
import com.crm.dashboard.DirectorDtos.DebtorsSection;
import com.crm.dashboard.DirectorDtos.FunnelSection;
import com.crm.dashboard.DirectorDtos.OperatorsSection;
import com.crm.dashboard.DirectorDtos.TrialsSection;
import com.crm.entity.DirectorDailyStat;
import com.crm.exception.CodedException;
import com.crm.repository.DirectorDailyStatRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code GET /api/dashboard/director} xulosasi (director-dashboard §4.1). Bo'limlar rol bo'yicha
 * (§7 #15 qarori): SA — hammasi; ADMIN — funnel, attendance, trials, operators; ACCOUNTANT —
 * collections, debtors; SALES_HEAD — funnel, operators. Ruxsatsiz bo'lim — null va {@code meta.hiddenSections}.
 *
 * <p>O'tgan kun (DAY) — yakuniy snapshot bo'lsa undan ({@code SNAPSHOT}), aks holda jonli. Natija
 * {@code app.dashboard.cache-seconds} (60) soniya keshlanadi.
 */
@Service
@Slf4j
public class DirectorDashboardService {

    public enum Section {
        FUNNEL("funnel"), COLLECTIONS("collections"), DEBTORS("debtors"), ATTENDANCE("attendance"),
        TRIALS("trials"), RETENTION("retention"), OPERATORS("operators");

        public final String key;

        Section(String key) {
            this.key = key;
        }
    }

    public record Meta(LocalDate date, String period, LocalDate from, LocalDate to, LocalDateTime asOf,
                       String timezone, String source, List<String> hiddenSections) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Summary(Meta meta, FunnelSection funnel, CollectionsSection collections, DebtorsSection debtors,
                          AttendanceSection attendance, TrialsSection trials, OperatorsSection operators,
                          List<String> headline) {
    }

    public record Params(String period, LocalDate date, LocalDate from, LocalDate to, Long operatorId,
                         Long teacherId, boolean includeImported) {
    }

    private final FunnelMetricsService funnel;
    private final CollectionsMetricsService collections;
    private final DebtorMetricsService debtors;
    private final AttendanceMetricsService attendance;
    private final TrialMetricsService trials;
    private final OperatorMetricsService operators;
    private final DirectorDailyStatRepository statRepository;
    private final ObjectMapper objectMapper;
    private final Clock billingClock;
    private final Cache<String, Summary> cache;

    public DirectorDashboardService(FunnelMetricsService funnel, CollectionsMetricsService collections,
                                    DebtorMetricsService debtors, AttendanceMetricsService attendance,
                                    TrialMetricsService trials, OperatorMetricsService operators,
                                    DirectorDailyStatRepository statRepository, ObjectMapper objectMapper,
                                    Clock billingClock, DashboardProperties properties) {
        this.funnel = funnel;
        this.collections = collections;
        this.debtors = debtors;
        this.attendance = attendance;
        this.trials = trials;
        this.operators = operators;
        this.statRepository = statRepository;
        this.objectMapper = objectMapper;
        this.billingClock = billingClock;
        this.cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(Math.max(1, properties.getCacheSeconds())))
            .maximumSize(500)
            .build();
    }

    // ── Rollar ──────────────────────────────────────────────────────────

    public static Set<Section> visibleSections() {
        if (BillingAuth.hasAnyRole("SUPER_ADMIN")) {
            return EnumSet.allOf(Section.class);
        }
        Set<Section> s = EnumSet.noneOf(Section.class);
        if (BillingAuth.hasAnyRole("ADMIN")) {
            s.addAll(EnumSet.of(Section.FUNNEL, Section.ATTENDANCE, Section.TRIALS, Section.OPERATORS));
        }
        if (BillingAuth.hasAnyRole("ACCOUNTANT")) {
            s.addAll(EnumSet.of(Section.COLLECTIONS, Section.DEBTORS));
        }
        if (BillingAuth.hasAnyRole("SALES_HEAD")) {
            // Sotuv bo'limi rahbari — faqat sotuv bo'limlari (lid voronkasi va operatorlar)
            s.addAll(EnumSet.of(Section.FUNNEL, Section.OPERATORS));
        }
        return s;
    }

    public static void requireSection(Section section) {
        if (!visibleSections().contains(section)) {
            throw CodedException.forbidden("dashboard.section.forbidden", section.key);
        }
    }

    public DashboardPeriod period(Params p) {
        return DashboardPeriod.of(p.period(), p.date(), p.from(), p.to(), LocalDateTime.now(billingClock));
    }

    // ── Xulosa ──────────────────────────────────────────────────────────

    public Summary summary(Params params) {
        Set<Section> visible = visibleSections();
        DashboardPeriod p = period(params);
        String key = visible + "|" + p.granularity() + "|" + p.from() + "|" + p.to() + "|" + params.operatorId()
            + "|" + params.teacherId() + "|" + params.includeImported() + "|" + p.today();
        return cache.get(key, k -> compute(p, params, visible, false));
    }

    /** Keshsiz, snapshot'siz jonli hisob — kunlik snapshot yozish uchun (§3.6). */
    public Summary computeLive(DashboardPeriod p, Params params, Set<Section> visible) {
        return compute(p, params, visible, true);
    }

    @Transactional(readOnly = true)
    public Summary compute(DashboardPeriod p, Params params, Set<Section> visible, boolean forceLive) {
        boolean pastDay = !forceLive && p.granularity() == DashboardPeriod.Granularity.DAY && p.to().isBefore(p.today());
        Set<String> sources = new TreeSet<>();
        FunnelMetricsService.Filter ff = new FunnelMetricsService.Filter(params.includeImported(), params.operatorId());
        boolean defaultFilters = !params.includeImported() && params.operatorId() == null && params.teacherId() == null;

        FunnelSection fs = null;
        if (visible.contains(Section.FUNNEL)) {
            fs = pastDay && defaultFilters ? fromSnapshot(p.to(), Section.FUNNEL, FunnelSection.class, sources) : null;
            if (fs == null) {
                sources.add("LIVE");
                fs = new FunnelSection(funnel.activity(p, ff),
                    p.granularity() == DashboardPeriod.Granularity.DAY ? null : funnel.cohort(p, ff));
            }
        }
        CollectionsSection cs = null;
        if (visible.contains(Section.COLLECTIONS)) {
            cs = pastDay ? fromSnapshot(p.to(), Section.COLLECTIONS, CollectionsSection.class, sources) : null;
            if (cs == null) {
                sources.add("LIVE");
                cs = collections.summary(p);
            }
        }
        DebtorsSection ds = null;
        if (visible.contains(Section.DEBTORS)) {
            ds = debtors.summary(p);
            sources.add("NONE".equals(ds.source()) ? "SNAPSHOT" : ds.source());
        }
        AttendanceSection as = null;
        if (visible.contains(Section.ATTENDANCE)) {
            as = pastDay && params.teacherId() == null
                ? fromSnapshot(p.to(), Section.ATTENDANCE, AttendanceSection.class, sources) : null;
            if (as == null) {
                sources.add("LIVE");
                as = attendance.summary(p, params.teacherId());
            }
        }
        TrialsSection ts = null;
        if (visible.contains(Section.TRIALS)) {
            sources.add("LIVE");
            ts = trials.summary(p);
        }
        OperatorsSection os = null;
        if (visible.contains(Section.OPERATORS)) {
            sources.add("LIVE");
            os = operators.summary(p);
        }

        List<String> hidden = new ArrayList<>();
        for (Section s : List.of(Section.FUNNEL, Section.COLLECTIONS, Section.DEBTORS, Section.ATTENDANCE,
                Section.TRIALS, Section.OPERATORS)) {
            if (!visible.contains(s)) {
                hidden.add(s.key);
            }
        }
        String source = sources.size() == 1 ? sources.iterator().next() : (sources.isEmpty() ? "LIVE" : "MIXED");
        Meta meta = new Meta(p.date(), p.granularity().name(), p.from(), p.to(), p.asOf(), "Asia/Tashkent",
            source, hidden);
        return new Summary(meta, fs, cs, ds, as, ts, os, headline(fs, cs, ds, as, ts, os));
    }

    private <T> T fromSnapshot(LocalDate day, Section section, Class<T> type, Set<String> sources) {
        return statRepository.findById(new DirectorDailyStat.Key(day, section.key))
            .filter(DirectorDailyStat::isFinalized)
            .map(st -> {
                try {
                    T value = objectMapper.readValue(st.getPayload(), type);
                    sources.add("SNAPSHOT");
                    return value;
                } catch (Exception e) {
                    log.warn("Snapshot o'qilmadi {} {}: {}", day, section.key, e.getMessage());
                    return null;
                }
            }).orElse(null);
    }

    // ── Sarlavha (Telegram bilan bir xil matn, §4.1, §5) ────────────────

    public static List<String> headline(FunnelSection fs, CollectionsSection cs, DebtorsSection ds,
                                        AttendanceSection as, TrialsSection ts, OperatorsSection os) {
        List<String> lines = new ArrayList<>();
        if (fs != null && fs.activity() != null) {
            var a = fs.activity();
            lines.add(a.leadsCreated() + " lid → " + a.visited() + " tashrif → "
                + a.firstPayments().total() + " birinchi to'lov");
        }
        if (cs != null) {
            lines.add(cs.due().count() + " muddati kelgan → " + cs.collected().count() + " undirildi (o'z vaqtida "
                + cs.onTime().count() + ", kechikib " + cs.late().count() + "), " + cs.pending().count() + " kutilmoqda");
        }
        if (ds != null && ds.count() != null) {
            lines.add(ds.count() + " qarzdor, " + money(ds.amount()) + " so'm"
                + (ds.newToday() != null ? " (bugun +" + ds.newToday()
                    + (ds.clearedToday() != null ? " / −" + ds.clearedToday() : "") + ")" : ""));
        }
        if (as != null) {
            lines.add(as.planned() + " darsdan " + as.taken() + " tasida davomat qilindi"
                + (as.missing() > 0 ? " (" + as.missing() + " ta yo'q)" : ""));
        }
        if (ts != null) {
            lines.add("Sinov: " + ts.cohort() + " kelgan → " + ts.buckets().get("D0").count() + " shu kuni to'ladi");
        }
        if (os != null) {
            lines.add("Operatorlar: javob medianasi "
                + (os.medianFirstResponseMin() != null ? os.medianFirstResponseMin() + " daq" : "—")
                + ", javobsiz " + os.noResponse() + " lid, muddati o'tgan vazifa " + os.tasksOverdueOpen());
        }
        return lines;
    }

    static String money(BigDecimal v) {
        if (v == null) {
            return "—";
        }
        DecimalFormatSymbols sym = new DecimalFormatSymbols(Locale.ROOT);
        sym.setGroupingSeparator(' ');
        return new DecimalFormat("#,##0", sym).format(v);
    }

    public void invalidate() {
        cache.invalidateAll();
    }

    public static Section parseSection(String raw) {
        for (Section s : Section.values()) {
            if (s.key.equalsIgnoreCase(raw)) {
                return s;
            }
        }
        throw CodedException.badRequest("dashboard.param.invalid", "section");
    }

}
