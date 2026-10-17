package com.crm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Mini App foydalanuvchisining bot xabari sozlamasi (V85): {@code topic} — {@code AppNotifyTopic.code()}.
 * Qator yo'q — yoqiq (standart). Identity o'chirilsa — CASCADE (PostgreSQL FK, V85).
 */
@Entity
@Table(name = "app_notification_prefs",
    uniqueConstraints = @UniqueConstraint(name = "ux_app_notification_prefs_identity_topic",
        columnNames = {"identity_id", "topic"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppNotificationPref {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "identity_id", nullable = false)
    private Long identityId;

    @Column(name = "topic", nullable = false, length = 30)
    private String topic;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
