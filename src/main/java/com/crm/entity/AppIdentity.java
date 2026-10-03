package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * O'quvchi yoki ota-onaning Telegram hisobi (docs/design/telegram-platform.md §3, §5) — Mini App
 * foydalanuvchisi. {@code users} jadvalida EMAS (phase5-audit Q1): admin paneliga kira olmaydi,
 * faqat {@code /api/app/**} va alohida JWT.
 *
 * <p>Bitta Telegram hisobi — bitta qator ({@code UNIQUE telegram_user_id}); qayta ulash shu qatorni
 * yangilaydi. Bog'langan o'quvchilar — {@link AppIdentityStudent}. Uzilganda (Mini App profilidan
 * yoki botda /stop) {@code status = UNLINKED} va {@code identityVersion} oshadi — berilgan JWT lar
 * darhol yaroqsiz.
 */
@Entity
@Table(name = "app_identities",
    uniqueConstraints = {
        @UniqueConstraint(name = "ux_app_identities_telegram_user", columnNames = "telegram_user_id"),
        // NULL lar takror hisoblanmaydi — faqat bog'langan xodim unikal (V68)
        @UniqueConstraint(name = "ux_app_identities_staff_user", columnNames = "staff_user_id")
    },
    indexes = @Index(name = "idx_app_identities_phone", columnList = "phone_canonical"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppIdentity {

    /**
     * STUDENT — faqat o'zi; PARENT — farzand(lar)i (o'zi ham o'qisa — u ham, §3.4); TEACHER — o'quvchisi yo'q,
     * faqat o'qituvchi rejimi (§11.4). O'qituvchi rejimi {@link #staffUserId} bilan beriladi va STUDENT/PARENT
     * bilan birga bo'lishi mumkin.
     */
    public enum Kind { STUDENT, PARENT, TEACHER }

    public enum Status { ACTIVE, UNLINKED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "telegram_user_id", nullable = false)
    private Long telegramUserId;

    /** Shaxsiy chat id (private chatda {@code chat.id == from.id}). */
    @Column(name = "chat_id")
    private Long chatId;

    @Column(name = "telegram_username", length = 64)
    private String telegramUsername;

    @Column(name = "first_name", length = 128)
    private String firstName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Kind kind;

    /** {@code PhoneUtils.canonical} — {@code +998XXXXXXXXX}. Logga yozilmaydi. */
    @Column(name = "phone_canonical", nullable = false, length = 20)
    private String phoneCanonical;

    /**
     * O'qituvchi rejimi (telegram-platform §11.4): TEACHER rolidagi xodim useri; null — rejim yo'q.
     * Bitta xodim — bitta identity (V68 unikal indeks; yangi ulanish eskisidan oladi).
     */
    @Column(name = "staff_user_id")
    private Long staffUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.ACTIVE;

    /** JWT {@code iv} claim'i — uzilganda oshadi. */
    @Column(name = "identity_version", nullable = false)
    @Builder.Default
    private Integer identityVersion = 0;

    @Column(name = "linked_at", nullable = false)
    private LocalDateTime linkedAt;

    @Column(name = "unlinked_at")
    private LocalDateTime unlinkedAt;

    /** APP (Mini App profili), BOT_STOP (/stop), NO_STUDENTS (qayta tekshiruvda o'quvchi qolmadi). */
    @Column(name = "unlink_reason", length = 30)
    private String unlinkReason;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        updatedAt = createdAt;
        if (identityVersion == null) {
            identityVersion = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }
}
