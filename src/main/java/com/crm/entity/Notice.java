package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private String publishedTo = "ALL";

    @Column(name = "notice_type", length = 30)
    private String noticeType = "GENERAL";

    @Column(name = "target_role", length = 30)
    private String targetRole;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "is_published")
    private Boolean isPublished = true;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;
}
