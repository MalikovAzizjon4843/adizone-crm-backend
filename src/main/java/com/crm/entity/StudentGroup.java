package com.crm.entity;

import com.crm.entity.converter.StudyFormatConverter;
import com.crm.entity.enums.PaymentStatus;
import com.crm.entity.enums.PaymentType;
import com.crm.entity.enums.StudyFormat;
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
    /**
     * ONLINE yoki OFFLINE. Mavjud yozuvlarda null — majburiy emas va
     * ortga qarab to'ldirilmaydi.
     */
    @Convert(converter = StudyFormatConverter.class)
    @Column(name = "study_format", length = 20)
    private StudyFormat studyFormat;

    @Column(name = "is_trial")
    @Builder.Default
    private Boolean isTrial = false;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "discount_percentage", precision = 5, scale = 2)
    @Builder.Default
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
    @Builder.Default
    private Integer lessonsAttended = 0;

    @Column(name = "first_lesson_date")
    private LocalDate firstLessonDate;

    @Column(name = "last_payment_date")
    private LocalDate lastPaymentDate;

    @Column(name = "next_payment_due")
    private LocalDate nextPaymentDue;

    /**
     * Billing v2: ledgerdan hosila (I7) — faqat {@code BillingSnapshotService} yozadi.
     * PAID / PENDING / OVERDUE / FROZEN / TRIAL (§4.2). Ustun turi o'zgarmagan (varchar).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", length = 20)
    @Builder.Default
    private PaymentStatus paymentStatus = PaymentStatus.PENDING;

    /** Eng eski to'lanmagan majburiyat sanasi (FIFO, §4.1); qarz yo'q — null. Snapshot. */
    @Column(name = "debt_since")
    private LocalDate debtSince;

    /** Keyingi to'lov summasi (§4.3). Snapshot. */
    @Column(name = "next_payment_amount", precision = 12, scale = 2)
    private BigDecimal nextPaymentAmount;

    @Column(name = "suspended_at")
    private LocalDateTime suspendedAt;

    @Column(name = "suspension_reason", columnDefinition = "TEXT")
    private String suspensionReason;

    /** GRADUATED, LEFT, TRANSFERRED, SUSPENDED, FROZEN, OTHER */
    @Column(name = "exit_reason", length = 50)
    private String exitReason;

    @Column(name = "exit_date")
    private LocalDate exitDate;

    /**
     * Billing v2: muzlatish sanasi (§6.7). Null emas — accrual yo'q, SG holati
     * FROZEN (qarz bo'lmasa). Unfreeze da null qilinadi; SG o'zi qayta faollashadi.
     */
    @Column(name = "frozen_from")
    private LocalDate frozenFrom;

    /**
     * Billing v2 migratsiyasi (§9.7): true — SG migratsiyadan chetlatilgan yoki qaytarilgan
     * ({@code MIGRATION_PENDING}); accrual o'tkazib yuboradi, qo'lda qayta qo'llanadi.
     */
    @Column(name = "billing_hold")
    private Boolean billingHold;

    // ── Direktor dashboardi (director-dashboard §1.5, §3.3, §3.4) ──
    /** Sinovda birinchi PRESENT/LATE davomat sanasi. */
    @Column(name = "trial_started_at")
    private LocalDate trialStartedAt;

    /** Sinovdan to'lovliga o'tgan kun. */
    @Column(name = "trial_converted_at")
    private LocalDate trialConvertedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "trial_outcome", length = 20)
    private com.crm.entity.enums.TrialOutcome trialOutcome;

    /** LIVE | BACKFILL. */
    @Column(name = "trial_source", length = 20)
    private String trialSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "exit_reason_code", length = 30)
    private com.crm.entity.enums.ExitReasonCode exitReasonCode;

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
        // Director dashboard (§3.3): har qanday yaratish yo'lida sinov natijasi boshlanadi
        if (Boolean.TRUE.equals(isTrial) && trialOutcome == null) {
            trialOutcome = com.crm.entity.enums.TrialOutcome.IN_TRIAL;
            trialSource = "LIVE";
        }
        if (paymentType == null) paymentType = PaymentType.MONTHLY;
        if (paymentStatus == null) paymentStatus = Boolean.TRUE.equals(isTrial) ? PaymentStatus.TRIAL : PaymentStatus.PAID;
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
