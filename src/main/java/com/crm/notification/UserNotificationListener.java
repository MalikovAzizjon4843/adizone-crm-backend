package com.crm.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Map;

/**
 * {@link NotificationEvent} — biznes tranzaksiyasi commit bo'lgandan KEYIN: bazaga yozish (alohida tranzaksiya),
 * so'ng har qabul qiluvchiga STOMP {@code /user/queue/notifications}. Xato biznes amalini buzmaydi — faqat log.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserNotificationListener {

    public static final String QUEUE = "/queue/notifications";

    private final NotificationService notificationService;
    private final SimpMessagingTemplate messagingTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(NotificationEvent event) {
        List<NotificationService.Delivered> delivered;
        try {
            delivered = notificationService.store(event);
        } catch (RuntimeException e) {
            log.error("Bildirishnoma yozilmadi ({}): {}", event.type(), e.toString());
            return;
        }
        for (NotificationService.Delivered d : delivered) {
            try {
                messagingTemplate.convertAndSendToUser(d.username(), QUEUE,
                    Map.of("type", "NOTIFICATION", "notification", d.notification(), "unreadCount", d.unreadCount()));
            } catch (RuntimeException e) {
                log.warn("Bildirishnoma push qilinmadi ({}): {}", event.type(), e.toString());
            }
        }
    }
}
