package com.crm.entity;

import com.crm.entity.enums.CashHandoverStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Kunlik kassa topshirish (V86, docs/design/cash-and-books.md §1.2.3): admin topshiradi → SA qabul qiladi
 * yoki rad etadi. Naqd Direktor kassasiga o'tkaziladi, farq — CASH_SHORTAGE / CASH_SURPLUS yozuvi;
 * naqdsiz — faqat solishtirish ({@link CashHandoverLine}).
 *
 * <p>Bir kassada bir vaqtda bitta SUBMITTED — PostgreSQL partial UNIQUE (V86); servis ham kassa qulfi
 * ostida tekshiradi.
 */
@Entity
@Table(name = "cash_handovers",
    indexes = {
        @Index(name = "idx_cash_handovers_register", columnList = "cash_register_id"),
        @Index(name = "idx_cash_handovers_status", columnList = "status")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CashHandover {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uuid", nullable = false, unique = true, updatable = false, length = 36)
    private String uuid;

    /** Topshirilayotgan ADMIN kassa. */
    @Column(name = "cash_register_id", nullable = false)
    private Long cashRegisterId;

    /** Qabul qilgan DIRECTOR kassa (qabulda yoziladi). */
    @Column(name = "target_cash_register_id")
    private Long targetCashRegisterId;

    /** Topshirilgan kun (Toshkent). */
    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Enumerated(value = EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CashHandoverStatus status;

    /** Topshirish paytidagi razmen. */
    @Column(name = "float_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal floatAmount;

    /** Kutilgan naqd = topshirish paytidagi {@code cash_balance − float_amount}. */
    @Column(name = "expected_cash", nullable = false, precision = 15, scale = 2)
    private BigDecimal expectedCash;

    /** Admin sanab topshirgan naqd. */
    @Column(name = "declared_cash", nullable = false, precision = 15, scale = 2)
    private BigDecimal declaredCash;

    /** Direktor sanagan naqd (qabulda) — shu summa Direktor kassasiga o'tadi. */
    @Column(name = "counted_cash", precision = 15, scale = 2)
    private BigDecimal countedCash;

    /** counted − expected: manfiy — kamomad, musbat — ortiqcha. */
    @Column(name = "cash_diff", precision = 15, scale = 2)
    private BigDecimal cashDiff;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "diff_note", columnDefinition = "TEXT")
    private String diffNote;

    @Column(name = "reject_reason", columnDefinition = "TEXT")
    private String rejectReason;

    @Column(name = "submitted_by")
    private Long submittedBy;

    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    /** Cut-over: eski yozuvlarni bog'lash uchun yaratilgan (docs/ops/cash-cutover.sql). */
    @Builder.Default
    @Column(name = "legacy", nullable = false)
    private boolean legacy = false;

    @Column(name = "idempotency_key", unique = true, length = 100)
    private String idempotencyKey;

    @PrePersist
    void onCreate() {
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
        }
    }
}
