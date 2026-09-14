package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "leads")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Lead {

    /** Seed birinchi bosqichga shu kodni beradi. */
    public static final String DEFAULT_STATUS = "NEW";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @UuidGenerator
    @Column(unique = true, nullable = false, updatable = false)
    private UUID uuid;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(name = "parent_phone", length = 32)
    private String parentPhone;

    @Column(length = 255)
    private String address;

    @Column(length = 100)
    private String course;

    @Column(length = 20)
    private String format;

    /**
     * Bosqich kodi — {@code lead_stages.code} ga mos keladi.
     *
     * <p>Enum EMAS: bosqichlar endi bazada va buyurtmachi ularni o'zi
     * qo'shadi. Tekshiruv {@code LeadStageService} keshida, ustun esa
     * avvalgidek matn — migratsiya kerak emas.
     */
    @Column(name = "status", length = 50, nullable = false)
    @Builder.Default
    private String status = DEFAULT_STATUS;

    @Column(length = 30)
    @Builder.Default
    private String source = "WEBSITE";

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column
    @Builder.Default
    private Boolean converted = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id")
    private Student student;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_user_id")
    private User assignedUser;

    @Column(name = "assigned_at")
    private LocalDateTime assignedAt;

    /**
     * Ommaviy import partiyasining belgisi. Qo'lda yaratilgan lidlarda null.
     * Noto'g'ri import qilinganda shu belgi bo'yicha butun partiya
     * o'chiriladi ({@code DELETE /api/leads/import/{batch}}).
     */
    @Column(name = "import_batch", length = 50)
    private String importBatch;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) {
            status = DEFAULT_STATUS;
        }
        if (source == null) {
            source = "WEBSITE";
        }
        if (format == null) {
            format = "OFFLINE";
        }
        if (converted == null) {
            converted = false;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
