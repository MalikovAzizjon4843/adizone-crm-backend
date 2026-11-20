package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Taklif / shikoyat — "murojaat" (V89, docs/design/telegram-platform.md §12). O'quvchi botida yoki Mini App'da
 * yuboriladi, CRM'da ({@code /api/appeals}) ko'rib chiqiladi.
 *
 * <p><b>Anonimlik</b>: {@link #anonymous} bo'lsa {@link #appIdentityId}, {@link #studentId}, {@link #parentId},
 * {@link #contactName}, {@link #contactPhone} SAQLANMAYDI (bazada CHECK). Yuboruvchiga javob yetkazish uchun faqat
 * {@link #telegramChatId} qoladi — u hech bir API javobida chiqmaydi.
 */
@Entity
@Table(name = "appeals",
    uniqueConstraints = @UniqueConstraint(name = "ux_appeals_number", columnNames = "number"),
    indexes = {
        @Index(name = "idx_appeals_assignee", columnList = "assignee_user_id"),
        @Index(name = "idx_appeals_teacher", columnList = "teacher_id")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Appeal {

    public enum Type { SUGGESTION, COMPLAINT, TEACHER, SERVICE, PAYMENT, OTHER }

    public enum Status {
        NEW, IN_PROGRESS, RESOLVED, REJECTED;

        public boolean closed() {
            return this == RESOLVED || this == REJECTED;
        }
    }

    public enum Source { BOT, MINIAPP }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code MRJ-00012} — {@code appeal_number_seq}. */
    @Column(name = "number", nullable = false, length = 20)
    private String number;

    @Enumerated(value = EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private Type type;

    @Column(name = "text", nullable = false, length = 3000)
    private String text;

    @Column(name = "anonymous", nullable = false)
    private boolean anonymous;

    @Enumerated(value = EnumType.STRING)
    @Column(name = "source", nullable = false, length = 10)
    private Source source;

    /** Yetkazish kaliti (shaxsiy chat). API'da hech qachon chiqmaydi. */
    @Column(name = "telegram_chat_id", nullable = false)
    private Long telegramChatId;

    /** Bot javoblari tili: {@code uz} | {@code ru}; null — {@code uz}. */
    @Column(name = "language", length = 5)
    private String language;

    @Column(name = "app_identity_id")
    private Long appIdentityId;

    @Column(name = "student_id")
    private Long studentId;

    @Column(name = "parent_id")
    private Long parentId;

    /** Ulanmagan va anonim bo'lmagan yuboruvchi o'zi kiritgan ism. */
    @Column(name = "contact_name", length = 150)
    private String contactName;

    /** Ulanmagan va anonim bo'lmagan yuboruvchi telefoni ({@code PhoneUtils.canonical}). */
    @Column(name = "contact_phone", length = 20)
    private String contactPhone;

    /** Faqat {@link Type#TEACHER} uchun (ixtiyoriy). Shu o'qituvchi murojaatni hech qachon ko'rmaydi. */
    @Column(name = "teacher_id")
    private Long teacherId;

    @Enumerated(value = EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.NEW;

    @Column(name = "assignee_user_id")
    private Long assigneeUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** Birinchi yuborilgan (ichki bo'lmagan) xodim javobi. */
    @Column(name = "first_response_at")
    private LocalDateTime firstResponseAt;

    /** RESOLVED / REJECTED bo'lgan vaqt; qayta ochilsa — null. */
    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;
}
