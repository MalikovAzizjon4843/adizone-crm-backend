package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Universal audit izi: kim, qachon, nima qildi.
 *
 * <p>username / userRole / entityLabel — ataylab SNAPSHOT (FK emas).
 * Foydalanuvchi o'chirilsa yoki ismi o'zgarsa ham log o'zgarmasin;
 * o'chirilgan obyektning nomi ham log ichida qolishi kerak.
 */
@Entity
@Table(name = "audit_logs", indexes = {
    @Index(name = "idx_audit_created", columnList = "created_at DESC"),
    @Index(name = "idx_audit_user", columnList = "user_id"),
    @Index(name = "idx_audit_entity", columnList = "entity_type, entity_id"),
    @Index(name = "idx_audit_action", columnList = "action")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** Tizim amallarida null. */
    @Column(name = "user_id")
    private Long userId;

    @Column(length = 100)
    private String username;

    @Column(name = "user_role", length = 30)
    private String userRole;

    /** CREATE / UPDATE / DELETE / LOGIN / LOGIN_FAILED / EXPORT / IMPORT / PAYMENT / REPAIR */
    @Column(nullable = false, length = 30)
    private String action;

    @Column(name = "entity_type", length = 50)
    private String entityType;

    @Column(name = "entity_id")
    private Long entityId;

    /** O'qish uchun nom — obyekt o'chsa ham qoladi. */
    @Column(name = "entity_label", length = 255)
    private String entityLabel;

    @Column(length = 500)
    private String summary;

    /** {"changes":[{"field":..,"old":..,"new":..}]} */
    @Column(name = "details_json", columnDefinition = "TEXT")
    private String detailsJson;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
