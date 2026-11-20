package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Murojaat yozishmasi (V89): {@link AuthorType#SENDER} — yuboruvchi (bot reply, "Javob yozish", Mini App);
 * {@link AuthorType#STAFF} — xodim. {@link #internal} — faqat xodimlar uchun izoh, yuboruvchiga yuborilmaydi va
 * Mini App'da ko'rinmaydi.
 */
@Entity
@Table(name = "appeal_messages",
    indexes = @Index(name = "idx_appeal_messages_appeal", columnList = "appeal_id, id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppealMessage {

    public enum AuthorType { SENDER, STAFF }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "appeal_id", nullable = false)
    private Long appealId;

    @Enumerated(value = EnumType.STRING)
    @Column(name = "author_type", nullable = false, length = 10)
    private AuthorType authorType;

    @Column(name = "author_user_id")
    private Long authorUserId;

    @Column(name = "text", nullable = false, length = 3000)
    private String text;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** STAFF: bot xabari navbatga (outbox) qo'yildi; SENDER: doim true (qabul qilingan). */
    @Column(name = "delivered", nullable = false)
    private boolean delivered;

    @Column(name = "internal", nullable = false)
    private boolean internal;
}
