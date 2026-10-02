package com.crm.entity;

import com.crm.entity.converter.ExamPaymentStatusConverter;
import com.crm.entity.converter.ExamRegistrationStatusConverter;
import com.crm.entity.enums.ExamPaymentStatus;
import com.crm.entity.enums.ExamRegistrationStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Imtihonga yozilish (leaves-exams-contracts §4). Pullik imtihonda yozilish = kassaga kirim
 * ({@code cashTransactionId}); bekor qilish = kassaga REVERSAL ({@code refundCashTransactionId}).
 * Bir o'quvchi bir imtihonga faqat bitta faol (CANCELLED emas) yozilishga ega — V62 qisman UNIQUE.
 */
@Entity
@Table(name = "exam_registrations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExamRegistration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exam_id", nullable = false)
    private Exam exam;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    /**
     * To'lov holati (ustun {@code payment_status}, API da {@code paymentStatus}). Java nomi boshqa: o'quvchi/yozilma
     * {@code setPaymentStatus} i faqat BillingSnapshotService da (billing-v2 I7, BillingArchitectureTest).
     */
    @Convert(converter = ExamPaymentStatusConverter.class)
    @Column(name = "payment_status", length = 20)
    @Builder.Default
    private ExamPaymentStatus feeStatus = ExamPaymentStatus.FREE;

    /** Yozilish paytidagi {@code exams.fee} (snapshot). */
    @Column(name = "amount_due", precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal amountDue = BigDecimal.ZERO;

    /** Kassaga tushgan summa. */
    @Column(name = "amount_paid", precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal amountPaid = BigDecimal.ZERO;

    @Column(name = "registration_date")
    private LocalDate registrationDate;

    @Convert(converter = ExamRegistrationStatusConverter.class)
    @Column(length = 20)
    @Builder.Default
    private ExamRegistrationStatus status = ExamRegistrationStatus.REGISTERED;

    @Column(columnDefinition = "TEXT")
    private String notes;

    /** Kassa INCOME yozuvi (pullik imtihon). */
    @Column(name = "cash_transaction_id")
    private Long cashTransactionId;

    /** Bekor qilishdagi kassa REVERSAL yozuvi. */
    @Column(name = "refund_cash_transaction_id")
    private Long refundCashTransactionId;

    @Column(name = "receipt_number", length = 32)
    private String receiptNumber;

    /** {@code Idempotency-Key} — takror bosish ikkinchi kirim yozmaydi. */
    @Column(name = "idempotency_key", length = 64, unique = true)
    private String idempotencyKey;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by")
    private User cancelledBy;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (registrationDate == null) {
            registrationDate = LocalDate.now();
        }
    }
}
