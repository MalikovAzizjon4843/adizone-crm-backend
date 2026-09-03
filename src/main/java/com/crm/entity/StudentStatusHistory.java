package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "student_status_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StudentStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @Column(name = "from_status", length = 50)
    private String fromStatus;

    @Column(name = "to_status", length = 50)
    private String toStatus;

    @Column(length = 100)
    private String reason;

    @Column(columnDefinition = "TEXT")
    private String notes;

    /**
     * Muzlatish paytidagi balans. Ilgari bu son {@code notes} matni ichiga
     * qo'shib yuborilardi va frontend uni regex bilan ajratib olardi.
     * Eski yozuvlarda null — o'sha qatorlar uchun frontend eski izohni
     * o'z holicha ko'rsatadi.
     */
    @Column(name = "balance_snapshot", precision = 12, scale = 2)
    private BigDecimal balanceSnapshot;

    /** Kelajakdagi strukturaviy qo'shimchalar uchun. Hozircha null. */
    @Column(name = "meta_json", columnDefinition = "TEXT")
    private String metaJson;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "changed_by")
    private User changedBy;

    @Column(name = "changed_at")
    private LocalDateTime changedAt;

    @PrePersist
    protected void onCreate() {
        if (changedAt == null) changedAt = LocalDateTime.now();
    }
}
