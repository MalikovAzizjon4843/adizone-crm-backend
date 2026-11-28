package com.crm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Qo'lda ko'chirishni (chiqarish TRANSFERRED + yangi guruhga qo'shish) transfer'ga tenglash izi (V93):
 * eski yozilma → yangi yozilma bog'lanishi. {@code UNIQUE (old, new)} — ta'mir bir marta qo'llanadi (idempotentlik).
 * Pul harakati ledger'da (PERIOD_REFUND, TRANSFER_OUT/IN); bu jadval faqat bog'lanish va xulosa.
 */
@Entity
@Table(name = "enrollment_transfer_links",
    uniqueConstraints = @UniqueConstraint(name = "uk_enrollment_transfer_links_pair",
        columnNames = {"old_student_group_id", "new_student_group_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EnrollmentTransferLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(name = "old_student_group_id", nullable = false)
    private Long oldStudentGroupId;

    @Column(name = "new_student_group_id", nullable = false)
    private Long newStudentGroupId;

    /** REPAIR_MANUAL_TRANSFER. */
    @Column(name = "source", nullable = false, length = 40)
    private String source;

    /** REANCHOR | REFUND_ONLY. */
    @Column(name = "mode", nullable = false, length = 20)
    private String mode;

    @Column(name = "continued_anchor")
    private LocalDate continuedAnchor;

    @Column(name = "refunded_amount", precision = 12, scale = 2)
    private BigDecimal refundedAmount;

    @Column(name = "moved_amount", precision = 12, scale = 2)
    private BigDecimal movedAmount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
