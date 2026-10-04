package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Hold'dagi yozilmani CRM'dan qo'llash yozuvi (V77): Idempotency-Key, sabab, langar o'zgarishi, natija.
 * {@code idempotency_key} UNIQUE — V77 indeksi.
 */
@Entity
@Table(name = "billing_held_applications", indexes = {
    @Index(name = "idx_billing_held_applications_sg", columnList = "student_group_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BillingHeldApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "student_group_id", nullable = false)
    private Long studentGroupId;

    @Column(name = "migration_run_id", nullable = false)
    private Long migrationRunId;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "reason", nullable = false, length = 1000)
    private String reason;

    @Column(name = "anchor_before")
    private LocalDate anchorBefore;

    @Column(name = "anchor_after")
    private LocalDate anchorAfter;

    @Column(name = "plan_hash", length = 64)
    private String planHash;

    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @Column(name = "applied_by", length = 100)
    private String appliedBy;

    @Column(name = "applied_at", nullable = false)
    private LocalDateTime appliedAt;
}
