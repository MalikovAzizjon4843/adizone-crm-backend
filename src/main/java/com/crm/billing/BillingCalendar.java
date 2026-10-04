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
 * <p><b>Davr zanjiri.</b> Keyingi yoziladigan davr oxirgi YOZILGAN davr boshidan keyingi birinchi langar-kuni sanasi
 * ({@link #firstUnbilledStart}); davr oxiri — panjaradagi navbatdagi boshdan bir kun oldin ({@link #endOf}). Panjara
 * bilan mos davrlarda bu aynan {@code start(n+1)}. Eski qoida (29–31 → oy oxiri) bilan yozilgan oxirgi davr
 * (29.09–30.10) dan keyin — 29.10; eski davr oxiri yangi davr yozilganda {@code 28.10} ga qisqartiriladi (ustma-ust yo'q).
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
     * Keyingi yoziladigan davr boshi (QAROR 1, §14.7): langardan keyin ({@code start ≥ anchor}) yozilgan davrlar bo'lsa —
     * eng oxirgisining BOSHIDAN keyingi birinchi langar-kuni sanasi; bo'lmasa — langar. Saqlangan {@code period_end}
     * ga qaralmaydi: eski qoida (29–31 → oy oxiri) bilan yozilgan davr oxiri (masalan 29.09–30.10) panjarani surmaydi —
     * keyingisi 29.10 (eski davr oxiri yozishda {@code AccrualService} tomonidan 28.10 ga qisqartiriladi).
     * Oldingi langarning davrlari (re-anchor, unfreeze — {@code start < anchor}) hisobga olinmaydi.
     */
    public static LocalDate firstUnbilledStart(LocalDate anchor, Collection<Span> billed) {
        LocalDate lastStart = null;
        for (Span s : billed) {
            if (s.start() != null && !s.start().isBefore(anchor)
                    && (lastStart == null || s.start().isAfter(lastStart))) {
                lastStart = s.start();
            }
        }
        return lastStart != null ? following(anchor, lastStart) : anchor;
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
