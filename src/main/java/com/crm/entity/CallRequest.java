package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** CRM'dan "telefonimdan qo'ng'iroq qil" so'rovi (V90, docs/design/calls.md §6). */
@Entity
@Table(name = "call_requests",
    indexes = @Index(name = "idx_call_requests_user_status", columnList = "user_id, status, expires_at"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CallRequest {

    public enum Status {
        PENDING, DELIVERED, DIALED, CANCELLED, EXPIRED;

        public boolean open() {
            return this == PENDING || this == DELIVERED;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "device_id")
    private Long deviceId;

    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    @Column(name = "lead_id")
    private Long leadId;

    @Column(name = "student_id")
    private Long studentId;

    @Enumerated(value = EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    @Builder.Default
    private Status status = Status.PENDING;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Column(name = "dialed_at")
    private LocalDateTime dialedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;
}
