package com.crm.billing;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Collection;

/**
 * Billing kuni va davr chegaralari — docs/design/billing-v2.md §3.1, §14.7 (sof funksiyalar).
 *
 * <pre>
 * billingDay      = day(anchor)                                   // 1..31
 * start(0)        = anchor
 * start(n), n ≥ 1 = dayOf(YearMonth.from(anchor).plusMonths(n), billingDay)
 * dayOf(ym, d)    = min(d, ym.lengthOfMonth())                    // QAROR 1 (§14.7): 29.09 → 29.10, 31.01 → 28.02 → 31.03
 * end(n)          = start(n+1) − 1 kun
 * </pre>
 *
 * <p><b>Davr zanjiri.</b> Keyingi yoziladigan davr panjaradan emas, oxirgi YOZILGAN davrdan topiladi
 * ({@link #firstUnbilledStart}): langardan keyin yozilgan davr bo'lsa — uning saqlangan oxiridan keyingi kun, davr
 * oxiri — panjaradagi navbatdagi boshdan bir kun oldin ({@link #endOf}). Panjara bilan mos davrlarda bu aynan
 * {@code start(n+1)}; eski qoida (29–31 → oy oxiri) bilan yozilgan davrdan keyin esa bitta "o'tish" davri
 * ({@code oxirgi oxir + 1 … navbatdagi langar kuni − 1}) va undan keyin langar kuni bo'yicha — ustma-ust ham, bo'shliq ham yo'q.
 *
 * <p>Proratsiya yo'q: birinchi davr langardan boshlanadi va to'liq narx olinadi.
 */
public final class BillingCalendar {

    private BillingCalendar() {
    }

    /** Yozilgan (yoki rejadagi) davr chegaralari. */
    public record Span(LocalDate start, LocalDate end) {
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

    /** Oyda langar kuni bo'lmasa — oyning oxirgi kuni (QAROR 1, §14.7). */
    static LocalDate dayOf(YearMonth ym, int day) {
        return ym.atDay(Math.min(day, ym.lengthOfMonth()));
    }

    /**
     * Keyingi yoziladigan davr boshi: langardan keyin ({@code start ≥ anchor}) yozilgan davrlar bo'lsa — eng kech
     * tugaganining saqlangan oxiridan keyingi kun; bo'lmasa — langar. Oldingi langarning davrlari (re-anchor,
     * unfreeze — {@code start < anchor}) hisobga olinmaydi.
     */
    public static LocalDate firstUnbilledStart(LocalDate anchor, Collection<Span> billed) {
        LocalDate lastEnd = null;
        for (Span s : billed) {
            if (s.start() != null && s.end() != null && !s.start().isBefore(anchor)
                    && (lastEnd == null || s.end().isAfter(lastEnd))) {
                lastEnd = s.end();
            }
        }
        return lastEnd != null ? lastEnd.plusDays(1) : anchor;
    }

    /** {@code start} dan boshlanadigan davr oxiri: panjaradagi {@code start} dan keyingi birinchi boshdan bir kun oldin. */
    public static LocalDate endOf(LocalDate anchor, LocalDate start) {
        int n = indexOf(anchor, start);
        return start(anchor, Math.max(n, 0) + 1).minusDays(1);
    }

    /** Zanjirdagi navbatdagi davr boshi. */
    public static LocalDate following(LocalDate anchor, LocalDate start) {
        return endOf(anchor, start).plusDays(1);
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
