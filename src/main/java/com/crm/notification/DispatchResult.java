package com.crm.notification;

/**
 * {@link NotificationService#dispatch} natijasi (statistika va testlar uchun).
 *
 * @param bell       qo'ng'iroqcha qabul qiluvchilari (yozish commit'dan keyin; takror kalitlilar shu yerda ham sanaladi)
 * @param telegram   outbox'ga yangi qo'yilgan bot xabarlari
 * @param duplicates bot: dedupe kaliti band — qayta qo'yilmadi
 * @param unlinked   o'quvchi Telegram'ga ulanmagan — jim o'tkazildi (xato emas)
 * @param disabled   o'quvchi kanali flag bilan o'chiq ({@link NotifyProperties}) — o'tkazilgan o'quvchilar / identity'lar
 * @param optedOut   foydalanuvchi shu mavzuni o'zi o'chirgan ({@code /api/app/notification-settings}) — identity'lar
 */
public record DispatchResult(int bell, int telegram, int duplicates, int unlinked, int disabled, int optedOut) {

    public static final DispatchResult EMPTY = new DispatchResult(0, 0, 0, 0, 0, 0);

    public DispatchResult(int bell, int telegram, int duplicates, int unlinked) {
        this(bell, telegram, duplicates, unlinked, 0, 0);
    }

    public DispatchResult(int bell, int telegram, int duplicates, int unlinked, int disabled) {
        this(bell, telegram, duplicates, unlinked, disabled, 0);
    }

    public DispatchResult plus(DispatchResult o) {
        return new DispatchResult(bell + o.bell, telegram + o.telegram, duplicates + o.duplicates,
            unlinked + o.unlinked, disabled + o.disabled, optedOut + o.optedOut);
    }
}
