package com.crm.billing;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** §3.1, §14.7 (QAROR 1): keyingi davr = langar kuni; oyda u kun bo'lmasa — oyning oxirgi kuni. */
class BillingCalendarTest {

    private static LocalDate d(int y, int m, int day) {
        return LocalDate.of(y, m, day);
    }

    @Test
    void midMonthAnchor() {
        LocalDate a = d(2026, 9, 15);
        assertThat(BillingCalendar.start(a, 0)).isEqualTo(d(2026, 9, 15));
        assertThat(BillingCalendar.start(a, 1)).isEqualTo(d(2026, 10, 15));
        assertThat(BillingCalendar.start(a, 3)).isEqualTo(d(2026, 12, 15));
        assertThat(BillingCalendar.end(a, 0)).isEqualTo(d(2026, 10, 14));
        assertThat(BillingCalendar.lengthInDays(a, 0)).isEqualTo(30);
        assertThat(BillingCalendar.lengthInDays(a, 1)).isEqualTo(31);
        assertThat(BillingCalendar.lengthInDays(a, 2)).isEqualTo(30);
    }

    @Test
    void firstOfMonth() {
        LocalDate a = d(2026, 10, 1);
        assertThat(BillingCalendar.end(a, 0)).isEqualTo(d(2026, 10, 31));
        assertThat(BillingCalendar.start(a, 1)).isEqualTo(d(2026, 11, 1));
        assertThat(BillingCalendar.lengthInDays(a, 1)).isEqualTo(30);
    }

    /** T1.5: 31.01.2027 → 28.02, 31.03, 30.04. */
    @Test
    void endOfMonthRule_31() {
        LocalDate a = d(2027, 1, 31);
        assertThat(BillingCalendar.start(a, 1)).isEqualTo(d(2027, 2, 28));
        assertThat(BillingCalendar.start(a, 2)).isEqualTo(d(2027, 3, 31));
        assertThat(BillingCalendar.start(a, 3)).isEqualTo(d(2027, 4, 30));
        assertThat(BillingCalendar.end(a, 0)).isEqualTo(d(2027, 2, 27));
        assertThat(BillingCalendar.lengthInDays(a, 0)).isEqualTo(28);
        assertThat(BillingCalendar.lengthInDays(a, 1)).isEqualTo(31);
        assertThat(BillingCalendar.lengthInDays(a, 2)).isEqualTo(30);
    }

    /** QAROR 1: 29.01.2027 → 28.02 → 29.03 → 29.04 (oy oxiri emas). */
    @Test
    void anchorDay_29() {
        LocalDate a = d(2027, 1, 29);
        assertThat(BillingCalendar.start(a, 0)).isEqualTo(d(2027, 1, 29));
        assertThat(BillingCalendar.start(a, 1)).isEqualTo(d(2027, 2, 28));
        assertThat(BillingCalendar.start(a, 2)).isEqualTo(d(2027, 3, 29));
        assertThat(BillingCalendar.start(a, 3)).isEqualTo(d(2027, 4, 29));
        // buyurtmachi misoli: 29.09 → 29.10
        assertThat(BillingCalendar.start(d(2026, 9, 29), 1)).isEqualTo(d(2026, 10, 29));
    }

    /** QAROR 1: 30.01.2027 → 28.02 → 30.03 → 30.04. */
    @Test
    void anchorDay_30() {
        LocalDate a = d(2027, 1, 30);
        assertThat(BillingCalendar.start(a, 1)).isEqualTo(d(2027, 2, 28));
        assertThat(BillingCalendar.start(a, 2)).isEqualTo(d(2027, 3, 30));
        assertThat(BillingCalendar.start(a, 3)).isEqualTo(d(2027, 4, 30));
        assertThat(BillingCalendar.end(a, 1)).isEqualTo(d(2027, 3, 29));
    }

    /** Kabisa yili: 30.01.2028 → 29.02.2028 → 30.03; 29.01.2028 → 29.02 → 29.03. */
    @Test
    void leapYear() {
        LocalDate a = d(2028, 1, 30);
        assertThat(BillingCalendar.start(a, 1)).isEqualTo(d(2028, 2, 29));
        assertThat(BillingCalendar.start(a, 2)).isEqualTo(d(2028, 3, 30));
        assertThat(BillingCalendar.lengthInDays(a, 0)).isEqualTo(30);     // 30.01–28.02
        assertThat(BillingCalendar.lengthInDays(a, 1)).isEqualTo(30);     // 29.02–29.03
        assertThat(BillingCalendar.start(d(2028, 1, 29), 1)).isEqualTo(d(2028, 2, 29));
        assertThat(BillingCalendar.start(d(2028, 1, 29), 2)).isEqualTo(d(2028, 3, 29));
        assertThat(BillingCalendar.start(d(2028, 1, 31), 1)).isEqualTo(d(2028, 2, 29));
    }

    /** Zanjir: yozilgan davr yo'q — langar; bor — oxirgi davr boshidan keyingi langar kuni; oldingi langar davrlari hisobga olinmaydi. */
    @Test
    void firstUnbilledStart_nextAnchorDayAfterLastStart() {
        LocalDate a = d(2026, 9, 15);
        assertThat(BillingCalendar.firstUnbilledStart(a, java.util.List.of())).isEqualTo(a);
        assertThat(BillingCalendar.firstUnbilledStart(a, java.util.List.of(
            new BillingCalendar.Span(d(2026, 9, 15), d(2026, 10, 14)),
            new BillingCalendar.Span(d(2026, 10, 15), d(2026, 11, 14))))).isEqualTo(d(2026, 11, 15));
        // re-anchor: eski langarning davri (start < anchor) zanjirga kirmaydi
        assertThat(BillingCalendar.firstUnbilledStart(a, java.util.List.of(
            new BillingCalendar.Span(d(2026, 8, 20), d(2026, 9, 19))))).isEqualTo(a);
    }

    /**
     * Prod (sg 68, 73): langar 29.09, yagona davr eski qoida bilan 29.09–30.10 saqlangan. Saqlangan oxir panjarani
     * surmaydi — keyingi davr 29.10 (31.10 emas); langar 30.09 (eski 30.09–30.10) → 30.10.
     */
    @Test
    void oldRuleStoredEnd_doesNotShiftNextStart() {
        LocalDate a29 = d(2026, 9, 29);
        assertThat(BillingCalendar.firstUnbilledStart(a29, java.util.List.of(
            new BillingCalendar.Span(a29, d(2026, 10, 30))))).isEqualTo(d(2026, 10, 29));
        LocalDate a30 = d(2026, 9, 30);
        assertThat(BillingCalendar.firstUnbilledStart(a30, java.util.List.of(
            new BillingCalendar.Span(a30, d(2026, 10, 30))))).isEqualTo(d(2026, 10, 30));
    }

    /**
     * Eski qoida bilan yozilgan, panjaradan tashqari boshli davrdan keyin: langar 29.08, yozilgan 30.09–30.10 (eski).
     * Keyingisi 30.09 dan keyingi birinchi langar kuni — 29.10 (oxirgi davr boshidan oldinga qaytmaydi), keyin 29.11.
     */
    @Test
    void afterOffGridOldPeriod_nextIsAnchorDayAfterItsStart() {
        LocalDate a = d(2026, 8, 29);
        java.util.List<BillingCalendar.Span> old = java.util.List.of(
            new BillingCalendar.Span(d(2026, 8, 29), d(2026, 9, 29)),
            new BillingCalendar.Span(d(2026, 9, 30), d(2026, 10, 30)));
        LocalDate next = BillingCalendar.firstUnbilledStart(a, old);
        assertThat(next).isEqualTo(d(2026, 10, 29));
        assertThat(BillingCalendar.endOf(a, next)).isEqualTo(d(2026, 11, 28));
        assertThat(BillingCalendar.following(a, next)).isEqualTo(d(2026, 11, 29));
    }

    @Test
    void indexOf_findsCoveringPeriod() {
        LocalDate a = d(2027, 1, 31);
        assertThat(BillingCalendar.indexOf(a, d(2027, 1, 30))).isEqualTo(-1);
        assertThat(BillingCalendar.indexOf(a, d(2027, 1, 31))).isZero();
        assertThat(BillingCalendar.indexOf(a, d(2027, 2, 27))).isZero();
        assertThat(BillingCalendar.indexOf(a, d(2027, 2, 28))).isEqualTo(1);
        assertThat(BillingCalendar.indexOf(a, d(2027, 3, 30))).isEqualTo(1);
        assertThat(BillingCalendar.indexOf(a, d(2027, 3, 31))).isEqualTo(2);
        assertThat(BillingCalendar.indexOf(d(2026, 9, 15), d(2027, 9, 14))).isEqualTo(11);
    }
}
