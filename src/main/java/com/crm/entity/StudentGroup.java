package com.crm.entity;

import com.crm.entity.enums.PaymentType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "student_groups")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StudentGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    private Group group;

    @Column(name = "join_date", nullable = false)
    private LocalDate joinDate;

    @Column(name = "leave_date")
    private LocalDate leaveDate;

    @Column(name = "next_payment_date")
    private LocalDate nextPaymentDate;

    @Column(name = "payment_start_date")
    private LocalDate paymentStartDate;

    /** Admin tanlovi: 1-dars bepul. Default false (to'lovli). */
    @Column(name = "is_trial")
    private Boolean isTrial = false;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "discount_percentage", precision = 5, scale = 2)
    private BigDecimal discountPercentage = BigDecimal.ZERO;

    @Column(name = "monthly_price_override", precision = 12, scale = 2)
    private BigDecimal monthlyPriceOverride;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_type", length = 20)
    @Builder.Default
    private PaymentType paymentType = PaymentType.MONTHLY;

    /** Shu o'quvchi uchun dars narxi (kursdan yoki qo'lda). */
    @Column(name = "lesson_price", precision = 12, scale = 2)
    private BigDecimal lessonPrice;

    /** PER_LESSON: sotib olingan darslar soni */
    @Column(name = "lessons_purchased")
    @Builder.Default
    private Integer lessonsPurchased = 0;

    /** PER_LESSON: o'qilgan darslar (PRESENT/ABSENT/LATE) */
    @Column(name = "lessons_used")
    @Builder.Default
    private Integer lessonsUsed = 0;

    /** Guruh bo'yicha balans (audit trail bilan) */
    @Column(name = "balance", precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "lessons_attended")
    private Integer lessonsAttended = 0;

    @Column(name = "first_lesson_date")
    private LocalDate firstLessonDate;

    @Column(name = "last_payment_date")
    private LocalDate lastPaymentDate;

    @Column(name = "next_payment_due")
    private LocalDate nextPaymentDue;

    /** TRIAL, PENDING, PAID, OVERDUE, SUSPENDED, ARCHIVED, FROZEN */
    @Column(name = "payment_status", length = 20)
    private String paymentStatus = "PENDING";

    @Column(name = "suspended_at")
    private LocalDateTime suspendedAt;

    @Column(name = "suspension_reason", columnDefinition = "TEXT")
    private String suspensionReason;

    /** GRADUATED, LEFT, TRANSFERRED, SUSPENDED, FROZEN, OTHER */
    @Column(name = "exit_reason", length = 50)
    private String exitReason;

    @Column(name = "exit_date")
    private LocalDate exitDate;

    @Column(name = "exit_notes", columnDefinition = "TEXT")
    private String exitNotes;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (joinDate == null) joinDate = LocalDate.now();
        if (paymentStartDate == null) paymentStartDate = joinDate;
        if (nextPaymentDate == null) nextPaymentDate = paymentStartDate;
        if (isTrial == null) isTrial = false;
        if (paymentType == null) paymentType = PaymentType.MONTHLY;
        if (paymentStatus == null) paymentStatus = Boolean.TRUE.equals(isTrial) ? "TRIAL" : "PENDING";
        if (lessonsAttended == null) lessonsAttended = 0;
        if (lessonsPurchased == null) lessonsPurchased = 0;
        if (lessonsUsed == null) lessonsUsed = 0;
        if (balance == null) balance = BigDecimal.ZERO;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
