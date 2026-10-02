package com.crm.entity;

import com.crm.entity.enums.UserRole;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "notices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notice extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @UuidGenerator
    @Column(unique = true, nullable = false, updatable = false)
    private UUID uuid;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "notice_date")
    private LocalDate noticeDate;

    /** ALL, TEACHERS, STUDENTS, PARENTS */
    @Column(name = "published_to", length = 30)
    @Builder.Default
    private String publishedTo = "ALL";

    @Column(name = "notice_type", length = 30)
    @Builder.Default
    private String noticeType = "GENERAL";

    /** Eski bitta rol (legacy). Yangi yozuvlarda NULL — {@link #targetRoles} ishlatiladi. */
    @Column(name = "target_role", length = 30)
    private String targetRole;

    /**
     * Auditoriya — rollar to'plami (phase5-audit N-01, Q12). Bo'sh — hamma (legacy maydonlar ham
     * cheklamasa). Saqlashda {@code publishedTo} = {@code ALL} / {@code ROLES}, {@code targetRole} = NULL.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "notice_target_roles", joinColumns = @JoinColumn(name = "notice_id"))
    @Column(name = "role", length = 30, nullable = false)
    @Enumerated(EnumType.STRING)
    @BatchSize(size = 50)
    @Builder.Default
    private Set<UserRole> targetRoles = new HashSet<>();

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "is_published")
    @Builder.Default
    private Boolean isPublished = true;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;
}
