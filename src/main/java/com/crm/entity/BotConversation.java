package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Bot suhbat holati (V89): bir shaxsiy chat — bitta qator. {@code app.appeals.state-ttl-minutes} dan eski holat
 * yo'q hisoblanadi va {@code TelegramCleanupJob} o'chiradi.
 */
@Entity
@Table(name = "bot_conversations",
    indexes = @Index(name = "idx_bot_conversations_updated", columnList = "updated_at"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BotConversation {

    @Id
    @Column(name = "chat_id")
    private Long chatId;

    /** {@code APPEAL_NEW} | {@code APPEAL_REPLY}. */
    @Column(name = "flow", nullable = false, length = 30)
    private String flow;

    @Column(name = "step", nullable = false, length = 30)
    private String step;

    /** JSON. */
    @Column(name = "data", columnDefinition = "TEXT")
    private String data;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
