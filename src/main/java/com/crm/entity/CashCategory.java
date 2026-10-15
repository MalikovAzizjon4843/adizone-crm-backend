package com.crm.entity;

import com.crm.entity.enums.CashCategoryScope;
import com.crm.entity.enums.CashDirection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Kassa kirim/chiqim kategoriyasi (V86, docs/design/cash-and-books.md §1.2.2) — sozlanadigan jadval.
 * {@code code}, {@code direction} va {@code scope} o'zgarmaydi; SYSTEM kategoriyani qo'lda tanlab bo'lmaydi.
 */
@Entity
@Table(name = "cash_categories")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CashCategory {

    @Id
    @Column(name = "code", nullable = false, length = 40)
    private String code;

    @Enumerated(value = EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 3)
    private CashDirection direction;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Enumerated(value = EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 20)
    private CashCategoryScope scope;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Builder.Default
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;
}
