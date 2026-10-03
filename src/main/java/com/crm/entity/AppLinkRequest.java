package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Qo'lda raqam bilan ulash so'rovi (docs/design/telegram-platform.md §11.1): Mini App foydalanuvchisi telefonini
 * yozadi, raqam bazada bo'lsa so'rov PENDING bo'lib CRM'ga tushadi, SA / A / SALES_HEAD tasdiqlaydi yoki rad etadi.
 */
@Entity
@Table(name = "app_link_requests", indexes = {
    @Index(name = "idx_app_link_requests_status", columnList = "status, created_at"),
    @Index(name = "idx_app_link_requests_user", columnList = "telegram_user_id, created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppLinkRequest {

    public enum Status { PENDING, APPROVED, REJECTED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "telegram_user_id", nullable = false)
    private Long telegramUserId;

    @Column(name = "chat_id")
    private Long chatId;

    @Column(name = "telegram_username", length = 64)
    private String telegramUsername;

    @Column(name = "first_name", length = 128)
    private String firstName;

    @Column(name = "phone_canonical", nullable = false, length = 20)
    private String phoneCanonical;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    /** So'rov paytidagi topilganlar — CRM ro'yxati uchun ("O'quvchi: …; O'qituvchi: …"). Tasdiqda qayta moslanadi. */
    @Column(name = "match_summary", length = 1000)
    private String matchSummary;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    /** Tasdiqlanganda ulangan identity. */
    @Column(name = "identity_id")
    private Long identityId;
}
