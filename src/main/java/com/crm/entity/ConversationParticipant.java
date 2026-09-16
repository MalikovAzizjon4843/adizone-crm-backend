package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Foydalanuvchining bitta suhbatdagi a'zoligi va shaxsiy sozlamalari.
 *
 * <p>{@code lastReadMessageId} — oddiy {@code Long}, FK emas: xabar o'chsa
 * ham kursor buzilmasligi kerak va u faqat taqqoslash uchun ishlatiladi.
 * {@code null} — hali hech narsa o'qilmagan.
 *
 * <p>{@code leftAt} — guruhdan chiqqan sana. Qator o'chirilmaydi, aks holda
 * eski xabarlarning muallifligi va o'qilganlik tarixi yo'qoladi. Faol
 * ishtirokchi = {@code leftAt IS NULL}.
 */
@Entity
@Table(name = "conversation_participants",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_conv_participant",
        columnNames = {"conversation_id", "user_id"}),
    indexes = {
        @Index(name = "idx_conv_participants_user", columnList = "user_id")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConversationParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** O'qilmaganlarni hisoblash uchun kursor; null — hammasi o'qilmagan. */
    @Column(name = "last_read_message_id")
    private Long lastReadMessageId;

    @Column(name = "is_pinned", nullable = false)
    @Builder.Default
    private Boolean isPinned = false;

    /**
     * 1-bosqichda faqat saqlanadi — bildirishnomalar hali yo'q, shuning
     * uchun o'qilmaganlar hisobiga ta'sir qilmaydi.
     */
    @Column(name = "is_muted", nullable = false)
    @Builder.Default
    private Boolean isMuted = false;

    @Column(name = "joined_at", nullable = false)
    private LocalDateTime joinedAt;

    /** Guruhdan chiqqan payt; null — hali a'zo. */
    @Column(name = "left_at")
    private LocalDateTime leftAt;

    @PrePersist
    protected void onCreate() {
        if (joinedAt == null) {
            joinedAt = LocalDateTime.now();
        }
    }
}
