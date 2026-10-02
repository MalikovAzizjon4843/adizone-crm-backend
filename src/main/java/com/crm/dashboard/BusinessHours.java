package com.crm.dashboard;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Set;

/**
 * Ish vaqti daqiqalari (director-dashboard §7 #13): faqat ish kunlari va
 * {@code [workStart, workEnd)} oralig'i sanaladi; tun va dam olish kuni — 0.
 */
public final class BusinessHours {

    private final LocalTime start;
    private final LocalTime end;
    private final Set<DayOfWeek> days;

    public BusinessHours(LocalTime start, LocalTime end, Set<DayOfWeek> days) {
        this.start = start;
        this.end = end;
        this.days = days;
    }

    public static BusinessHours of(DashboardProperties p) {
        return new BusinessHours(p.getWorkStart(), p.getWorkEnd(), p.getWorkDays());
    }

    public long minutesBetween(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null || !to.isAfter(from)) {
            return 0;
        }
        long minutes = 0;
        for (LocalDate d = from.toLocalDate(); !d.isAfter(to.toLocalDate()); d = d.plusDays(1)) {
            if (!days.contains(d.getDayOfWeek())) {
                continue;
            }
            LocalDateTime dayStart = d.atTime(start);
            LocalDateTime dayEnd = d.atTime(end);
            LocalDateTime a = from.isAfter(dayStart) ? from : dayStart;
            LocalDateTime b = to.isBefore(dayEnd) ? to : dayEnd;
            if (b.isAfter(a)) {
                minutes += Duration.between(a, b).toMinutes();
            }
        }
        return minutes;
    }
}
