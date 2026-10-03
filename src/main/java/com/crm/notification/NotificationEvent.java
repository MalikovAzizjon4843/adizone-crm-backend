package com.crm.notification;

import com.crm.entity.enums.UserRole;

import java.util.Set;

/**
 * Biznes tranzaksiyasi ichida e'lon qilinadigan bildirishnoma hodisasi. Yozish va STOMP push — commit'dan
 * KEYIN ({@link UserNotificationListener}): amal rollback bo'lsa bildirishnoma ham yo'q.
 *
 * @param userIds       aniq qabul qiluvchilar (user id)
 * @param roles         shu rollardagi barcha faol xodimlar ham qabul qiladi (masalan SA, A)
 * @param excludeUserId amalni bajargan xodim — o'ziga yuborilmaydi (null — hech kim chiqarilmaydi)
 * @param collapse      true — shu tur + obyekt bo'yicha o'qilmagan bildirishnoma bo'lsa yangilanadi
 *                      (yangi qator emas; chat xabarlari)
 */
public record NotificationEvent(NotificationType type, Set<Long> userIds, Set<UserRole> roles, Long excludeUserId,
                                String title, String body, String link, String entityType, Long entityId,
                                boolean collapse) {

    public NotificationEvent {
        userIds = userIds == null ? Set.of() : Set.copyOf(userIds);
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }
}
