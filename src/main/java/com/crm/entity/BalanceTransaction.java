package com.crm.entity;

import com.crm.entity.enums.BalanceTransactionType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Pul daftari — faqat INSERT (I3). Yagona yozuvchi: {@code LedgerService.post}.
 *
 * <p>{@code effectiveDate} — majburiyat/kredit sanasi (FIFO uchun), {@code createdAt}
 * dan farq qiladi: masalan quvib yetilgan accrual 17.10 da yoziladi, lekin
 * {@code effectiveDate = 15.10}. Eski yozuvlarda V52 skripti uni
 * {@code created_at::date} bilan to'ldiradi; ustun JPA da nullable — aks holda
 * {@code ddl-auto: update} to'la jadvalga NOT NULL ustun qo'sha olmaydi.
 */
@Entity
@Table(name = "balance_transactions", indexes = {
    @Index(name = "idx_balance_tx_student", columnList = "student_id"),
    @Index(name = "idx_balance_tx_sg", columnList = "student_group_id"),
    @Index(name = "idx_balance_tx_created", columnList = "created_at"),
    @Index(name = "idx_balance_tx_sg_effective", columnList = "student_group_id, effective_date, id"),
    @Index(name = "idx_balance_tx_related", columnList = "related_tx_id"),
    @Index(name = "idx_balance_tx_migration", columnList = "migration_run_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BalanceTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_group_id")
    private StudentGroup studentGroup;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private BalanceTransactionType type;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, precision = 12, scale = 2)
    private BigDecimal balanceAfter;

    /** payment / attendance / bonus_penalty / cash_transaction id — qaysi biri turdan aniqlanadi. */
    @Column(name = "reference_id")
    private Long referenceId;

    /** Majburiyat yoki kredit sanasi (FIFO tartibi). */
    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    /** REVERSAL / *_REFUND / TRANSFER juftligi — asl yozuv. */
    @Column(name = "related_tx_id")
    private Long relatedTxId;

    /** PERIOD_CHARGE / PERIOD_REFUND → billing_periods.id. */
    @Column(name = "billing_period_id")
    private Long billingPeriodId;

    /** Migratsiya yozuvlari belgisi (qaytarish uchun). */
    @Column(name = "migration_run_id")
    private Long migrationRunId;

    @Column(columnDefinition = "TEXT")
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (effectiveDate == null) {
            effectiveDate = createdAt.toLocalDate();
        }
    }
}
