package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Qabul qilingan webhook update'lari — {@code update_id} bo'yicha takrorni tashlash uchun
 * (docs/design/telegram-platform.md §1): Telegram 2xx olmaguncha qayta yuboradi. 7 kundan keyin tozalanadi.
 */
@Entity
@Table(name = "telegram_updates",
    indexes = @Index(name = "idx_telegram_updates_received", columnList = "received_at"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TelegramUpdate {

    @Id
    @Column(name = "update_id")
    private Long updateId;

    @Column(length = 30)
    private String kind;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;
}
