package com.crm.notification;

import com.crm.entity.UserNotification;

import java.time.LocalDateTime;

/** Qo'ng'iroqcha qatori. {@code read} — {@code readAt != null}. */
public record UserNotificationResponse(Long id, String type, String title, String body, String link,
                                       String entityType, Long entityId, boolean read, LocalDateTime readAt,
                                       LocalDateTime createdAt) {

    public static UserNotificationResponse of(UserNotification n) {
        return new UserNotificationResponse(n.getId(), n.getType(), n.getTitle(), n.getBody(), n.getLink(),
            n.getEntityType(), n.getEntityId(), n.getReadAt() != null, n.getReadAt(), n.getCreatedAt());
    }
}
