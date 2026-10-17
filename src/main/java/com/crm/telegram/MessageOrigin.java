package com.crm.telegram;

/**
 * Bot xabarining kelib chiqishi — sokin soatlar (21:00–08:00, Asia/Tashkent) qarori shunga qarab
 * ({@link TelegramOutboxService#enqueue}, docs/design/notifications.md §2):
 * <ul>
 *   <li>{@link #AUTOMATIC} — tizim hodisasi (davomat, qarz, to'lov yaqinlashdi, dars eslatmasi, direktor xulosasi,
 *       vazifa muddati): sokin soatda HECH QAYSI priority bilan ketmaydi — {@code not_before} keyingi 08:00
 *       (ovoz bilan);</li>
 *   <li>{@link #MANUAL} — odam boshlagan (chat xabari, qo'lda chop etilgan e'lon, ulash so'roviga javob, ota-ona
 *       bildirgan sabab): darhol ketadi; sokin soatda ovozsiz ({@code disable_notification}).</li>
 * </ul>
 * Priority endi faqat navbat tartibi (HIGH oldin).
 */
public enum MessageOrigin {
    AUTOMATIC,
    MANUAL
}
