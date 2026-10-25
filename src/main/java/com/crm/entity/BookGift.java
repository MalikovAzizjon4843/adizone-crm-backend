package com.crm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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

/**
 * Bepul kitob (V87): birinchi kelgan kuni oylik to'liq to'lagan o'quvchiga darajasidagi kitob — bir marta
 * ({@code student_id} UNIQUE). Ombor kamayadi, kassaga yozilmaydi.
 */
@Entity
@Table(name = "book_gifts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookGift {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "student_id", nullable = false, unique = true)
    private Long studentId;

    @Column(name = "student_group_id", nullable = false)
    private Long studentGroupId;

    /** Shu kungi to'lov (shartni tasdiqlagan). */
    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "level_id", nullable = false)
    private Long levelId;

    @Column(name = "book_item_id", nullable = false)
    private Long bookItemId;

    @Column(name = "first_visit_date", nullable = false)
    private LocalDate firstVisitDate;

    /** Davomat hali belgilanmagan — bugun dars bor va oldin kelmagan, shuning uchun bugun deb olindi. */
    @Builder.Default
    @Column(name = "first_visit_assumed", nullable = false)
    private boolean firstVisitAssumed = false;

    @Column(name = "unit_cost", precision = 12, scale = 2)
    private BigDecimal unitCost;

    @Column(name = "gift_date", nullable = false)
    private LocalDate giftDate;

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
