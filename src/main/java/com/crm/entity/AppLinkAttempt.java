package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Botga yuborilgan kontakt bo'yicha bog'lash urinishi (docs/design/telegram-platform.md §3.4).
 * Telefon o'zi emas, SHA-256 xeshi saqlanadi. NOT_FOUND lar 24 soatda 5 tadan oshsa — vaqtincha rad.
 */
@Entity
@Table(name = "app_link_attempts",
    indexes = @Index(name = "idx_app_link_attempts_user", columnList = "telegram_user_id, created_at"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppLinkAttempt {

    /** MANUAL_* — qo'lda raqam kiritish (§11.1): so'rov yaratildi / raqam topilmadi. */
    public enum Result { LINKED, NOT_FOUND, REJECTED, LIMITED, MANUAL_REQUEST, MANUAL_NOT_FOUND }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "telegram_user_id", nullable = false)
    private Long telegramUserId;

    @Column(name = "phone_hash", length = 64)
    private String phoneHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Result result;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
