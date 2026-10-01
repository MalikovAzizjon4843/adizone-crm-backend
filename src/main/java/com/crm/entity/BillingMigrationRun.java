package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Billing v2 migratsiyasi — {@code billing_migration_runs} (§9.4–§9.7, §13 #20).
 *
 * <p>Dry-run hech narsa yozmaydi. Qator faqat egasi dry-run hisobotini tasdiqlaganda
 * (hash bilan) yaratiladi; apply faqat shu APPROVED qator bo'yicha va hisobot hash'i
 * hamda {@code max(balance_transactions.id)}, {@code max(payments.id)} o'zgarmagan bo'lsa.
 */
@Entity
@Table(name = "billing_migration_runs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BillingMigrationRun {

    public enum Status { APPROVED, APPLYING, APPLIED, APPLIED_WITH_ERRORS, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    private Status status;

    /** T — cutover sanasi (§9.1). */
    @Column(name = "cutover_date", nullable = false)
    private LocalDate cutoverDate;

    /** G — go-live sanasi (§9.1). */
    @Column(name = "go_live_date", nullable = false)
    private LocalDate goLiveDate;

    /** §13 #18: A14 SG lar uchun o'tgan davr narxi = eski to'lov payable. */
    @Column(name = "a14_use_payable", nullable = false)
    private boolean a14UsePayable;

    @Column(name = "report_hash", length = 64, nullable = false)
    private String reportHash;

    @Column(name = "max_tx_id")
    private Long maxTxId;

    @Column(name = "max_payment_id")
    private Long maxPaymentId;

    @Column(name = "sg_total")
    private Integer sgTotal;

    /** Hisobotni tasdiqlagan egasi (buyurtmachi) — §13 #20. */
    @Column(name = "approved_by_owner", length = 200, nullable = false)
    private String approvedByOwner;

    @Column(name = "approval_note", columnDefinition = "TEXT")
    private String approvalNote;

    /** Tasdiqni tizimga kiritgan SUPER_ADMIN. */
    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "approved_at", nullable = false)
    private LocalDateTime approvedAt;

    @Column(name = "applied_by", length = 100)
    private String appliedBy;

    @Column(name = "applied_at")
    private LocalDateTime appliedAt;

    /** {@code applied_at + 72 soat} (§9.7, §13 #20): shu vaqtgacha to'liq rollback mumkin. */
    @Column(name = "rollback_deadline")
    private LocalDateTime rollbackDeadline;

    @Column(name = "excluded_sg_ids", columnDefinition = "TEXT")
    private String excludedSgIds;

    @Column(name = "clear_overrides")
    private Boolean clearOverrides;

    private Integer migrated;
    private Integer held;
    private Integer failed;

    @Column(columnDefinition = "TEXT")
    private String errors;

    @Column(name = "summary_json", columnDefinition = "TEXT")
    private String summaryJson;
}
