package com.crm.entity;

import com.crm.entity.converter.MessageTypeConverter;
import com.crm.entity.enums.MessageType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Suhbatdagi bitta xabar.
 *
 * <p>Sahifalash va o'qilganlik kursori {@code id} bo'yicha ishlaydi:
 * u monoton o'sadi va bir xil soniyada kelgan ikki xabarni ham ajratadi,
 * {@code created_at} esa buni kafolatlamaydi.
 *
 * <p>{@code deletedAt} — yumshoq o'chirish. 1-bosqichda o'chirish
 * endpointi yo'q, ammo ustun boshidan turadi: keyin qo'shilganda eski
 * xabarlarni ko'chirish kerak bo'lmaydi.
 */
@Entity
@Table(name = "messages", indexes = {
    // Vaqt bo'yicha o'qish (oxirgi xabar, eksport)
    @Index(name = "idx_messages_conversation_created",
        columnList = "conversation_id, created_at DESC"),
    // Kursor bo'yicha sahifalash: WHERE conversation_id = ? AND id < ? ORDER BY id DESC
    @Index(name = "idx_messages_conversation_id_desc",
        columnList = "conversation_id, id DESC")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Message {

    /** Bitta xabar matnining chegarasi — servis ham, DTO ham shu qiymatga tayanadi. */
    public static final int TEXT_MAX = 4000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @UuidGenerator
    @Column(unique = true, nullable = false, updatable = false)
    private UUID uuid;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    @Column(name = "text", columnDefinition = "TEXT")
    private String text;

    @Convert(converter = MessageTypeConverter.class)
    @Column(name = "type", nullable = false, length = 20)
    @Builder.Default
    private MessageType type = MessageType.TEXT;

    /** 2-bosqich: javob berilgan xabar id si. FK emas — o'chgan xabarga ham ishora qoladi. */
    @Column(name = "reply_to_id")
    private Long replyToId;

    @Column(name = "edited_at")
    private LocalDateTime editedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
