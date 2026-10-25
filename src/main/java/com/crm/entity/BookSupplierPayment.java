package com.crm.entity;

import com.crm.entity.enums.PaymentMethod;
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
 * Yetkazib beruvchiga to'lov (V87): faqat SA, faqat Direktor kassasidan, kirim (kitob kelgan) bo'yicha, qisman
 * ham — kassa EXPENSE {@code BOOK_PURCHASE} ({@code cash_transactions.book_supplier_payment_id}).
 */
@Entity
@Table(name = "book_supplier_payments",
    indexes = {
        @Index(name = "idx_book_supplier_payments_receipt", columnList = "receipt_id"),
        @Index(name = "idx_book_supplier_payments_supplier", columnList = "supplier_id")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookSupplierPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uuid", nullable = false, unique = true, updatable = false, length = 36)
    private String uuid;

    @Column(name = "receipt_id", nullable = false)
    private Long receiptId;

    @Column(name = "supplier_id", nullable = false)
    private Long supplierId;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "cash_register_id", nullable = false)
    private Long cashRegisterId;

    @Enumerated(value = EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    private PaymentMethod paymentMethod;

    @Column(name = "cash_part", precision = 14, scale = 2)
    private BigDecimal cashPart;

    @Column(name = "card_part", precision = 14, scale = 2)
    private BigDecimal cardPart;

    @Column(name = "cash_transaction_id")
    private Long cashTransactionId;

    @Column(name = "paid_at", nullable = false)
    private LocalDate paidAt;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "idempotency_key", unique = true, length = 100)
    private String idempotencyKey;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
