package com.crm.notification;

/**
 * Hodisa kodi — CRM qo'ng'iroqchasi turi va bot xabari {@code event_code} i (docs/design/notifications.md).
 * Frontend ikonka va filtr uchun shu nomga tayanadi. Shablonlar — {@code messages*.properties}:
 * {@code notify.<TUR>.title}, {@code notify.<TUR>.body} (qo'ng'iroqcha, o'zbekcha) va
 * {@code notify.<TUR>.telegram} (bot, qabul qiluvchi tilida).
 *
 * <p>{@link #staffTelegram} — xodim qo'ng'iroqchadan tashqari, Telegram'ga ulangan bo'lsa (o'qituvchi rejimi,
 * {@code AppIdentity.staffUserId}) botda ham oladi.
 */
public enum NotificationType {
    /** Mini App: qo'lda ulash so'rovi (telegram-platform §11.1) → SA, A, SH. */
    APP_LINK_REQUEST(false),
    /** Mini App foydalanuvchisi EXTERNAL suhbatga yozdi (§11.3) → suhbatning xodim a'zolari; o'qilmagani yig'iladi. */
    CHAT_EXTERNAL(false),
    /** O'quvchi / ota-ona sabab bildirdi (§11.2) → guruh o'qituvchisi va shu kungi o'rinbosar. */
    ABSENCE_NOTICE(false),
    /** O'qituvchi o'tgan kun davomatini ochishni so'radi → SA, A. */
    ATTENDANCE_UNLOCK_REQUEST(false),
    /** Ochish so'rovi tasdiqlandi / rad etildi → so'ragan o'qituvchi. */
    ATTENDANCE_UNLOCK_DECIDED(false),
    /** Ta'til arizasi → SA, A. */
    LEAVE_REQUEST(false),
    /** Ta'til tasdiqlandi / rad etildi → ta'tildagi xodim (va ariza bergan, agar boshqa bo'lsa). */
    LEAVE_DECIDED(false),

    /** Ichki (xodimlar) suhbatida yangi xabar → boshqa a'zolar; o'qilmagani yig'iladi, suhbat o'qilsa o'qilgan. */
    CHAT_INTERNAL(true),
    /** Lid biriktirildi (qo'lda, ommaviy — bitta yig'ma, Meta / sayt avto) → mas'ul. */
    LEAD_ASSIGNED(false),
    /** E'lon chop etildi → auditoriya rollaridagi xodimlar. */
    NOTICE_NEW(true),
    /** Ochiq vazifa muddatiga 1 soat qoldi → egasi. */
    TASK_DUE_SOON(true),
    /** Ochiq vazifa muddati o'tdi → egasi. */
    TASK_OVERDUE(true),

    /** O'quvchi darsga kelmadi (ABSENT) → ulangan ota-ona va o'quvchining o'zi (bot). */
    STUDENT_ABSENT(false),
    /** To'lov muddati o'tdi (qarz) → ulangan ota-ona va o'quvchining o'zi (bot). */
    PAYMENT_OVERDUE(false),
    /** To'lov yaqinlashdi (bugun + N kun) → ulangan ota-ona va o'quvchi (bot). */
    PAYMENT_DUE_SOON(false),
    /** Dars eslatmasi (boshlanishidan N daqiqa oldin) → ulangan ota-ona va o'quvchi (bot). */
    LESSON_REMINDER(false),
    /** E'lon o'quvchi / ota-ona auditoriyasiga chop etildi → ulangan identity'lar (bot). */
    NOTICE_APP(false),
    /** Direktor kunlik xulosasi → {@code app.dashboard.digest.chat-ids} (bot). */
    DIRECTOR_DIGEST(false);

    private final boolean staffTelegram;

    NotificationType(boolean staffTelegram) {
        this.staffTelegram = staffTelegram;
    }

    public boolean staffTelegram() {
        return staffTelegram;
    }

    /** Shablon kaliti: {@code notify.<TUR>.<qism>}. */
    public String key(String part) {
        return key(null, part);
    }

    /** Variantli kalit: {@code notify.<TUR>.<variant>.<qism>} (masalan ommaviy biriktirish — {@code bulk}). */
    public String key(String variant, String part) {
        return "notify." + name() + (variant == null ? "" : "." + variant) + "." + part;
    }
}
