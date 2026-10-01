package com.crm.billing.support;

import com.crm.billing.BillingClockConfig;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Test soati: "bugun"ni test o'zi o'rnatadi (docs/design/billing-v2.md §12.1 —
 * {@code Clock.fixed(...)} ning o'zgaruvchan varianti, bitta Spring konteksti
 * bir nechta test orasida qayta ishlatilgani uchun).
 */
public class MutableClock extends Clock {

    private volatile Instant instant;
    private final ZoneId zone;

    public MutableClock() {
        this.zone = BillingClockConfig.ZONE;
        setDate(LocalDate.of(2026, 9, 15));
    }

    private MutableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    /** Shu sananing 12:00 (Toshkent) holatiga o'tadi. */
    public void setDate(LocalDate date) {
        this.instant = date.atTime(LocalTime.NOON).atZone(zone).toInstant();
    }

    public LocalDate today() {
        return LocalDate.ofInstant(instant, zone);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
