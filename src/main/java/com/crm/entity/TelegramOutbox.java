package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Bot xabarlari navbati (docs/design/telegram-platform.md §11.5 — §2.3 ning minimal varianti). Biznes amali
 * bilan bir tranzaksiyada yoziladi, {@code TelegramOutboxWorker} commit'dan keyin yuboradi.
 */
@Entity
@Table(name = "telegram_outbox",
    uniqueConstraints = @UniqueConstraint(name = "ux_telegram_outbox_dedupe", columnNames = "dedupe_key"),
    indexes = @Index(name = "idx_telegram_outbox_due", columnList = "status, not_before"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TelegramOutbox {

    public enum Status { PENDING, SENT, FAILED }

    /** HIGH — darhol (sokin soatda ovozsiz); NORMAL — sokin soatda 08:00 gacha kutadi. */
    public enum Priority { HIGH, NORMAL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String text;

    /** JSON matn ({@code inline_keyboard} …) yoki null. */
    @Column(name = "reply_markup", columnDefinition = "TEXT")
    private String replyMarkup;

    @Column(nullable = false)
    private Boolean silent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Priority priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @Column(nullable = false)
    private Integer attempts;

    @Column(name = "not_before", nullable = false)
    private LocalDateTime notBefore;

    @Column(name = "dedupe_key", length = 120)
    private String dedupeKey;

    @Column(name = "event_code", length = 40)
    private String eventCode;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;
}
