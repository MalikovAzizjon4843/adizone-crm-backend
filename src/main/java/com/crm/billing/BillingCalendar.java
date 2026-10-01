package com.crm.billing;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/**
 * Billing kuni va davr chegaralari — docs/design/billing-v2.md §3.1 (sof funksiyalar).
 *
 * <pre>
 * billingDay      = day(anchor)                                   // 1..31
 * start(0)        = anchor
 * start(n), n ≥ 1 = dayOf(YearMonth.from(anchor).plusMonths(n), billingDay)
 * dayOf(ym, d)    = d ≥ 29 ? ym.atEndOfMonth() : ym.atDay(d)      // 29–31 → oy oxiri (§13 #1)
 * end(n)          = start(n+1) − 1 kun
 * </pre>
 * Proratsiya yo'q: birinchi davr langardan boshlanadi va to'liq narx olinadi.
 */
public final class BillingCalendar {

    private BillingCalendar() {
    }

    public static LocalDate start(LocalDate anchor, int n) {
        if (n < 0) {
            throw new IllegalArgumentException("n ≥ 0 bo'lishi kerak");
        }
        if (n == 0) {
            return anchor;
        }
        return dayOf(YearMonth.from(anchor).plusMonths(n), anchor.getDayOfMonth());
    }

    public static LocalDate end(LocalDate anchor, int n) {
        return start(anchor, n + 1).minusDays(1);
    }

    public static long lengthInDays(LocalDate anchor, int n) {
        return ChronoUnit.DAYS.between(start(anchor, n), end(anchor, n)) + 1;
    }

    static LocalDate dayOf(YearMonth ym, int day) {
        return day >= 29 ? ym.atEndOfMonth() : ym.atDay(day);
    }

    /**
     * {@code date} tushadigan davr indeksi; {@code date < anchor} bo'lsa −1.
     * Oy ichida bir necha qadam: {@code start(n) ≤ date < start(n+1)}.
     */
    public static int indexOf(LocalDate anchor, LocalDate date) {
        if (date.isBefore(anchor)) {
            return -1;
        }
        int n = (int) ChronoUnit.MONTHS.between(YearMonth.from(anchor), YearMonth.from(date));
        n = Math.max(0, n - 1);
        while (!start(anchor, n + 1).isAfter(date)) {
            n++;
        }
        return n;
    }
}
