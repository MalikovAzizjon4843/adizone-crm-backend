package com.crm.entity;

import com.crm.entity.enums.PaymentMethod;
import com.crm.entity.enums.PaymentStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @UuidGenerator
    @Column(unique = true, nullable = false, updatable = false)
    private UUID uuid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id")
    private Group group;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_group_id")
    private StudentGroup studentGroup;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cash_register_id")
    private CashRegister cashRegister;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "payment_date", nullable = false)
    private LocalDate paymentDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method")
    private PaymentMethod paymentMethod = PaymentMethod.CASH;

    @Enumerated(EnumType.STRING)
    private PaymentStatus status = PaymentStatus.PAID;

    /** To'lov davri boshi (DB: period_from). */
    @Column(name = "period_from")
    private LocalDate periodStart;

    /** To'lov davri oxiri (DB: period_to). */
    @Column(name = "period_to")
    private LocalDate periodEnd;

    @Column(length = 500)
    private String description;

    @Column(name = "discount_amount", precision = 12, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "bonus_discount", precision = 12, scale = 2)
    private BigDecimal bonusDiscount = BigDecimal.ZERO;

    @Column(name = "receipt_number", length = 32, unique = true)
    private String receiptNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "received_by")
    private User receivedBy;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** API compatibility alias. */
    @Transient
    public LocalDate getPeriodFrom() {
        return periodStart;
    }

    public void setPeriodFrom(LocalDate periodFrom) {
        this.periodStart = periodFrom;
    }

    /** API compatibility alias. */
    @Transient
    public LocalDate getPeriodTo() {
        return periodEnd;
    }

    public void setPeriodTo(LocalDate periodTo) {
        this.periodEnd = periodTo;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (paymentDate == null) {
            paymentDate = LocalDate.now();
        }
        if (discountAmount == null) {
            discountAmount = BigDecimal.ZERO;
        }
        if (bonusDiscount == null) {
            bonusDiscount = BigDecimal.ZERO;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
