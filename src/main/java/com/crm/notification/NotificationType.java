package com.crm.notification;

/**
 * Xodim bildirishnomasi turlari (CRM qo'ng'iroqchasi). Frontend ikonka va filtr uchun shu nomga tayanadi.
 */
public enum NotificationType {
    /** Mini App: qo'lda ulash so'rovi (telegram-platform §11.1) → SA, A, SH. */
    APP_LINK_REQUEST,
    /** Mini App foydalanuvchisi EXTERNAL suhbatga yozdi (§11.3) → suhbatning xodim a'zolari; o'qilmagani yig'iladi. */
    CHAT_EXTERNAL,
    /** O'quvchi / ota-ona sabab bildirdi (§11.2) → guruh o'qituvchisi va shu kungi o'rinbosar. */
    ABSENCE_NOTICE,
    /** O'qituvchi o'tgan kun davomatini ochishni so'radi → SA, A. */
    ATTENDANCE_UNLOCK_REQUEST,
    /** Ochish so'rovi tasdiqlandi / rad etildi → so'ragan o'qituvchi. */
    ATTENDANCE_UNLOCK_DECIDED,
    /** Ta'til arizasi → SA, A. */
    LEAVE_REQUEST,
    /** Ta'til tasdiqlandi / rad etildi → ta'tildagi xodim (va ariza bergan, agar boshqa bo'lsa). */
    LEAVE_DECIDED
}
