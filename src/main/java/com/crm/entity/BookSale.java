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
 * Kitob sotuvi — bitta chek, savat (V87): o'quv va badiiy kitoblar birga; o'quvchiga YOKI tashqi xaridorga
 * (V87 CHECK). Kassa INCOME {@code BOOK_SALE} ({@code cash_transactions.book_sale_id}) — sotuvchining o'z kassasi.
 * Qaytarish yo'q.
 */
@Entity
@Table(name = "book_sales",
    indexes = {
        @Index(name = "idx_book_sales_date", columnList = "sale_date"),
        @Index(name = "idx_book_sales_register", columnList = "cash_register_id"),
        @Index(name = "idx_book_sales_student", columnList = "student_id")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookSale {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uuid", nullable = false, unique = true, updatable = false, length = 36)
    private String uuid;

    @Column(name = "receipt_number", nullable = false, unique = true, length = 32)
    private String receiptNumber;

    @Column(name = "student_id")
    private Long studentId;

    @Column(name = "buyer_name", length = 150)
    private String buyerName;

    @Column(name = "buyer_phone", length = 30)
    private String buyerPhone;

    @Column(name = "total_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalAmount;

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

    @Column(name = "sale_date", nullable = false)
    private LocalDate saleDate;

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
