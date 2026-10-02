package com.crm.entity;

import com.crm.entity.enums.BillingPeriodStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * MONTHLY yozilmaning bitta hisob davri — docs/design/billing-v2.md §3.3.
 *
 * <p>I4: {@code UNIQUE (student_group_id, period_start)} — davr bir marta
 * hisoblanadi. Oddiy (partial emas) constraint, shuning uchun {@code ddl-auto}
 * yangi jadval yaratganda ham hosil bo'ladi.
 *
 * <p>{@code fee}, {@code discountPercentage}, {@code amount} — yozilgan paytdagi
 * snapshot: narx keyin o'zgarsa ham bu davr qayta hisoblanmaydi (§3.4).
 */
@Entity
@Table(name = "billing_periods",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_billing_periods_sg_start",
        columnNames = {"student_group_id", "period_start"}),
    indexes = @Index(name = "idx_billing_periods_migration", columnList = "migration_run_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BillingPeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "student_group_id", nullable = false)
    private Long studentGroupId;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "fee", precision = 12, scale = 2)
    private BigDecimal fee;

    @Column(name = "discount_percentage", precision = 5, scale = 2)
    private BigDecimal discountPercentage;

    /** Chegirmadan keyingi, yaxlitlangan davr summasi ({@code c}). */
    @Column(name = "amount", precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private BillingPeriodStatus status;

    /** PERIOD_CHARGE yozuvi (MIGRATED / PREPAID_LEGACY va {@code amount = 0} da null). */
    @Column(name = "charge_tx_id")
    private Long chargeTxId;

    @Column(name = "refunded_amount", precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal refundedAmount = BigDecimal.ZERO;

    /** Migratsiya yaratgan qator (qisman rollback uchun belgi). */
    @Column(name = "migration_run_id")
    private Long migrationRunId;

    // ── Direktor dashboardi (director-dashboard §1.2, §3.2, G5) ──
    /** Muddat — billing kuni (= period_start). */
    @Column(name = "due_date")
    private LocalDate dueDate;

    /** {@code due_date + grace} (yozilgan paytdagi grace). */
    @Column(name = "grace_until")
    private LocalDate graceUntil;

    /** Davrni FIFO bo'yicha TO'LIQ yopgan kreditning effective_date si; null — yopilmagan. */
    @Column(name = "paid_on")
    private LocalDate paidOn;

    /** Yopgan kredit yozilgan payt (kiritish kechikishi uchun). */
    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "paid_tx_id")
    private Long paidTxId;

    /** FIFO | MIGRATION_REPLAY. */
    @Column(name = "coverage_source", length = 20)
    private String coverageSource;

    // ── Payroll v2 (payroll-v2 §8): davr yozilgan paytdagi o'qituvchi ──
    @Column(name = "teacher_id")
    private Long teacherId;

    /** {@link com.crm.entity.enums.TeacherAttribution}: LIVE | ESTIMATED. */
    @Column(name = "teacher_source", length = 20)
    private String teacherSource;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (refundedAmount == null) {
            refundedAmount = BigDecimal.ZERO;
        }
    }
}
