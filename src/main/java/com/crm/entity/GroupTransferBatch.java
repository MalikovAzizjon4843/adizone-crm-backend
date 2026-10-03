package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Guruhga ommaviy ko'chirish amali (phase6-api §5): kim, qachon, qayerdan qayerga, kimlarni. Idempotentlik —
 * {@code idempotency_key} UNIQUE; takroriy so'rov {@code result_json} dagi javobni qaytaradi (V65).
 */
@Entity
@Table(name = "group_transfer_batches")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupTransferBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "from_group_id", nullable = false)
    private Long fromGroupId;

    @Column(name = "target_group_id", nullable = false)
    private Long targetGroupId;

    @Column(name = "transfer_date", nullable = false)
    private LocalDate transferDate;

    /** Vergul bilan ajratilgan o'quvchi id lari (o'sish tartibida). */
    @Column(name = "student_ids", nullable = false, columnDefinition = "TEXT")
    private String studentIds;

    @Column(length = 500)
    private String note;

    /** UNIQUE (qisman, NULL dan tashqari) — V65 {@code uk_group_transfer_batches_key}. */
    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    /** So'rov mazmuni (from|target|ids|date) — bir kalit boshqa so'rov bilan kelsa 409. */
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
