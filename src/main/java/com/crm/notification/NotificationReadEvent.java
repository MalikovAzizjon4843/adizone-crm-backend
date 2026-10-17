package com.crm.notification;

/**
 * Xodimning bildirishnomalari server tomonda o'qilgan qilindi ({@link NotificationService#markEntityRead}) —
 * commit'dan keyin yangi o'qilmaganlar soni STOMP bilan yuboriladi ({@link UserNotificationListener}).
 */
public record NotificationReadEvent(Long userId) {
}
