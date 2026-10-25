package com.crm.entity;

import com.crm.entity.enums.BookKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Sotuv qatori (V87): narx — sotuv paytidagi {@code book_items.sale_price} nusxasi; tannarx — sotuv paytidagi
 * {@code avg_cost} nusxasi (faqat SA ko'radi; tannarxi hali to'ldirilmagan bo'lsa null); {@code kind} — hisobot uchun.
 */
@Entity
@Table(name = "book_sale_lines",
    indexes = @Index(name = "idx_book_sale_lines_sale", columnList = "sale_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookSaleLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sale_id", nullable = false)
    private Long saleId;

    @Column(name = "book_item_id", nullable = false)
    private Long bookItemId;

    @Enumerated(value = EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private BookKind kind;

    @Column(name = "qty", nullable = false)
    private int qty;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "unit_cost", precision = 12, scale = 2)
    private BigDecimal unitCost;

    @Column(name = "line_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal lineTotal;
}
