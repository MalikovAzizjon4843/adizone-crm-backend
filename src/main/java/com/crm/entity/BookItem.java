package com.crm.entity;

import com.crm.entity.enums.BookKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Kitob katalogi (V87): o'quv (darajasi bilan) yoki badiiy. {@code stockQty} — ombor qoldig'i, faqat
 * {@code BookStockService} o'zgartiradi (qator qulfi ostida, harakat jurnali bilan; bazada {@code CHECK ≥ 0}).
 * {@code avgCost} — o'rtacha tortilgan tannarx, faqat SUPER_ADMIN ko'radi.
 */
@Entity
@Table(name = "book_items")
@org.hibernate.annotations.Check(constraints = "stock_qty >= 0")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uuid", nullable = false, unique = true, updatable = false, length = 36)
    private String uuid;

    @Enumerated(value = EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private BookKind kind;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "author", length = 150)
    private String author;

    /** STUDY — majburiy, FICTION — null (V87 CHECK). */
    @Column(name = "level_id")
    private Long levelId;

    @Column(name = "sale_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal salePrice;

    @Builder.Default
    @Column(name = "stock_qty", nullable = false)
    private int stockQty = 0;

    @Column(name = "avg_cost", precision = 12, scale = 2)
    private BigDecimal avgCost;

    @Column(name = "min_stock", nullable = false)
    private int minStock;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
        }
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
