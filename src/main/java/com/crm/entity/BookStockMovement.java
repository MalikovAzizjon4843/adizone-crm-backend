package com.crm.entity;

import com.crm.entity.enums.BookMovementType;
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
import java.time.LocalDateTime;

/**
 * Ombor harakati jurnali (V87, faqat qo'shiladi): har o'zgarish — {@code qty_delta} va keyingi qoldiq
 * {@code qty_after} (≥ 0). {@code refType}/{@code refId} — kirim qatori, sotuv qatori, sovg'a.
 */
@Entity
@Table(name = "book_stock_movements",
    indexes = @Index(name = "idx_book_stock_movements_item", columnList = "book_item_id, created_at"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookStockMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "book_item_id", nullable = false)
    private Long bookItemId;

    @Enumerated(value = EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private BookMovementType type;

    @Column(name = "qty_delta", nullable = false)
    private int qtyDelta;

    @Column(name = "qty_after", nullable = false)
    private int qtyAfter;

    @Column(name = "unit_cost", precision = 12, scale = 2)
    private BigDecimal unitCost;

    @Column(name = "ref_type", length = 30)
    private String refType;

    @Column(name = "ref_id")
    private Long refId;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
