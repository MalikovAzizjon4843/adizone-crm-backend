package com.crm.dashboard;

import com.crm.exception.CodedException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

/**
 * Hisobot davri (director-dashboard §0): {@code DAY | WEEK | MONTH | CUSTOM}, Asia/Tashkent.
 * Hafta ISO (dushanba–yakshanba), oy kalendar oyi, CUSTOM ≤ 366 kun. "D kuni" =
 * {@code [D 00:00, D+1 00:00)}. {@code asOf} — hisob payti (hozir): kogorta ko'rsatkichlari
 * ("davrda kelganlar hozirgacha qayergacha yetdi") shu paytgacha o'lchanadi; o'tgan kunning
 * o'sha kechki muzlatilgan holati — {@code director_daily_stats} snapshot'i (§3.6).
 */
public record DashboardPeriod(Granularity granularity, LocalDate date, LocalDate from, LocalDate to,
                              LocalDate today, LocalDateTime now) {

    public enum Granularity { DAY, WEEK, MONTH, CUSTOM }

    public static final int MAX_CUSTOM_DAYS = 366;

    public static DashboardPeriod of(String period, LocalDate date, LocalDate from, LocalDate to,
                                     LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        Granularity g = parse(period);
        LocalDate d = date != null ? date : today;
        return switch (g) {
            case DAY -> new DashboardPeriod(g, d, d, d, today, now);
            case WEEK -> new DashboardPeriod(g, d,
                d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
                d.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)), today, now);
            case MONTH -> new DashboardPeriod(g, d, d.withDayOfMonth(1),
                d.with(TemporalAdjusters.lastDayOfMonth()), today, now);
            case CUSTOM -> {
                if (from == null || to == null || to.isBefore(from)
                        || ChronoUnit.DAYS.between(from, to) + 1 > MAX_CUSTOM_DAYS) {
                    throw CodedException.badRequest("dashboard.period.invalid", MAX_CUSTOM_DAYS);
                }
                yield new DashboardPeriod(g, to, from, to, today, now);
            }
        };
    }

    private static Granularity parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Granularity.DAY;
        }
        try {
            return Granularity.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw CodedException.badRequest("dashboard.period.invalid", MAX_CUSTOM_DAYS);
        }
    }

    public LocalDateTime start() {
        return from.atStartOfDay();
    }

    public LocalDateTime endExclusive() {
        return to.plusDays(1).atStartOfDay();
    }

    public boolean includesToday() {
        return !today.isBefore(from) && !today.isAfter(to);
    }

    /** Hisob kuni (bugun). */
    public LocalDate asOfDate() {
        return today;
    }

    /** Hisob payti (hozir). */
    public LocalDateTime asOf() {
        return now;
    }

    public boolean contains(LocalDate d) {
        return d != null && !d.isBefore(from) && !d.isAfter(to);
    }

    public boolean contains(LocalDateTime t) {
        return t != null && !t.isBefore(start()) && t.isBefore(endExclusive());
    }
}
