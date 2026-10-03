package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Mini App identity'si ko'ra oladigan o'quvchi (docs/design/telegram-platform.md §3.4, §5).
 * {@code /api/app/**} dagi har {@code studentId} shu jadval bo'yicha tekshiriladi (IDOR).
 *
 * <p>Ro'yxat ulanishda va har {@code POST /api/app/auth} da (≤ 30 daqiqada bir) telefon bo'yicha
 * qayta quriladi: CRM'da telefon o'zgarsa yoki o'quvchi arxivlansa, ruxsat o'zi tushadi.
 */
@Entity
@Table(name = "app_identity_students",
    uniqueConstraints = @UniqueConstraint(name = "ux_app_identity_students", columnNames = {"identity_id", "student_id"}),
    indexes = @Index(name = "idx_app_identity_students_student", columnList = "student_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppIdentityStudent {

    /** SELF — telefon o'quvchining o'zida; PARENT — ota-ona telefoni. */
    public enum Relation { SELF, PARENT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "identity_id", nullable = false)
    private Long identityId;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Relation relation;

    @Column(name = "linked_at", nullable = false)
    private LocalDateTime linkedAt;
}
