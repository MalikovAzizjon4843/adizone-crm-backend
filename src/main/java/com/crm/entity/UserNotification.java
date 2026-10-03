package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Xodim bildirishnomasi — CRM qo'ng'iroqchasi (phase5-audit S-04, V71). Matn yozish paytida tayyorlanadi
 * (faqat o'zbekcha). Legacy {@code notifications} jadvali (kodsiz, 0 qator) ishlatilmaydi.
 * 90 kundan eskilari {@code UserNotificationRetentionJob} bilan o'chiriladi.
 */
@Entity
@Table(name = "user_notifications", indexes = {
    @Index(name = "idx_user_notifications_user_created", columnList = "user_id, created_at"),
    @Index(name = "idx_user_notifications_created", columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** {@code NotificationType} nomi: APP_LINK_REQUEST, CHAT_EXTERNAL, ABSENCE_NOTICE, … */
    @Column(nullable = false, length = 40)
    private String type;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(length = 1000)
    private String body;

    /** Frontend yo'li (masalan {@code /leaves/12}); ishonchli havola — {@code entityType} + {@code entityId}. */
    @Column(length = 500)
    private String link;

    @Column(name = "entity_type", length = 40)
    private String entityType;

    @Column(name = "entity_id")
    private Long entityId;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
