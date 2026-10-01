package com.crm.billing;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** §3.1 jadvali va T1.5 — oy oxiri qoidasi (§13 #1 qarori). */
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

    /** T1.5: 29.01.2027 → 28.02, 31.03 (oy oxiri, 29.03 emas). */
    @Test
    void endOfMonthRule_29() {
        LocalDate a = d(2027, 1, 29);
        assertThat(BillingCalendar.start(a, 0)).isEqualTo(d(2027, 1, 29));
        assertThat(BillingCalendar.start(a, 1)).isEqualTo(d(2027, 2, 28));
        assertThat(BillingCalendar.start(a, 2)).isEqualTo(d(2027, 3, 31));
        assertThat(BillingCalendar.start(a, 3)).isEqualTo(d(2027, 4, 30));
    }

    /** T1.5: kabisa yili — 30.01.2028 → 29.02.2028. */
    @Test
    void leapYear() {
        LocalDate a = d(2028, 1, 30);
        assertThat(BillingCalendar.start(a, 1)).isEqualTo(d(2028, 2, 29));
        assertThat(BillingCalendar.start(a, 2)).isEqualTo(d(2028, 3, 31));
        assertThat(BillingCalendar.lengthInDays(a, 0)).isEqualTo(30);
        assertThat(BillingCalendar.lengthInDays(a, 1)).isEqualTo(31);
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
