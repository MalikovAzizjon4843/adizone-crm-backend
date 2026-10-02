package com.crm.entity;

import com.crm.entity.converter.PayrollStatusConverter;
import com.crm.entity.enums.PaymentMethod;
import com.crm.entity.enums.PayrollStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "payroll")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Payroll extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @UuidGenerator
    @Column(unique = true, nullable = false, updatable = false)
    private UUID uuid;

    /** Backward compat — TEACHER uchun */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "teacher_id")
    private Teacher teacher;

    /** Asosiy egasi (admin/sales/teacher user) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false)
    private Integer month;

    @Column(nullable = false)
    private Integer year;

    @Column(name = "basic_salary", precision = 12, scale = 2)
    private BigDecimal basicSalary;

    @Column(precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal allowances = BigDecimal.ZERO;

    @Column(precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal deductions = BigDecimal.ZERO;

    @Column(name = "net_salary", precision = 12, scale = 2)
    private BigDecimal netSalary;

    @Column(name = "bonus_penalty_adjustment", precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal bonusPenaltyAdjustment = BigDecimal.ZERO;

    @Column(name = "paid_student_count")
    private Integer paidStudentCount;

    /**
     * TEACHER: to'lagan birliklar — davrlar + PER_LESSON ulushlari (kasr bo'lishi mumkin, §11 #2).
     * {@code paidStudentCount} — elementlar soni (davr + yozilma).
     */
    @Column(name = "paid_student_units", precision = 10, scale = 4)
    private BigDecimal paidStudentUnits;

    /** Hisobda ishlatilgan qoida — ishlatilgan qoidani tahrirlash 409 (§11 #3). */
    @Column(name = "salary_rule_id")
    private Long salaryRuleId;

    @Column(name = "new_student_count")
    private Integer newStudentCount;

    @Column(name = "kpi_applied")
    private Boolean kpiApplied;

    @Column(name = "kpi_amount", precision = 12, scale = 2)
    private BigDecimal kpiAmount;

    /** Tuzilgan JSON (payroll-v2 §6) — javobda obyekt. v1 yozuvlarida eski shakl. */
    @Column(name = "calculation_details", columnDefinition = "TEXT")
    private String calculationDetails;

    /** Hisob formulasi versiyasi: 2 — payroll v2; null — v1 yozuvi. */
    @Column(name = "calc_version")
    private Integer calcVersion;

    @Column(name = "payment_date")
    private LocalDate paymentDate;

    /** To'lanmagan oylik uchun null bo'lishi mumkin. */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 20)
    private PaymentMethod paymentMethod;

    /** Holat faqat amallar orqali o'zgaradi (payroll-v2 §1). Bazadagi v1 {@code PENDING} → DRAFT. */
    @Convert(converter = PayrollStatusConverter.class)
    @Column(length = 20, nullable = false)
    @Builder.Default
    private PayrollStatus status = PayrollStatus.DRAFT;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cash_register_id")
    private CashRegister cashRegister;

    /** To'lovdagi kassa chiqimi (EXPENSE); bekor qilishda shu yozuv teskari yoziladi. */
    @Column(name = "cash_transaction_id")
    private Long cashTransactionId;

    /** {@code pay} takrorini aniqlash uchun ({@code Idempotency-Key}). */
    @Column(name = "pay_idempotency_key", length = 64)
    private String payIdempotencyKey;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by")
    private User approvedBy;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "paid_by")
    private User paidBy;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by")
    private User cancelledBy;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;
}
