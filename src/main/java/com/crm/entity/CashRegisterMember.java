package com.crm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Kassa a'zosi (V86, docs/design/cash-and-books.md §1.2.1): ADMIN / ACCOUNTANT faqat a'zo bo'lgan ADMIN
 * kassasini ko'radi va unga yozadi. "Bir admin — bitta kassa" — {@code PUT /api/cash-registers/{id}/members}
 * da tekshiriladi (biriktirish boshqa kassadan ko'chiradi); bazada UNIQUE(user_id) yo'q.
 */
@Entity
@Table(name = "cash_register_members",
    uniqueConstraints = @UniqueConstraint(name = "ux_cash_register_members_register_user",
        columnNames = {"cash_register_id", "user_id"}),
    indexes = @Index(name = "idx_cash_register_members_user", columnList = "user_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CashRegisterMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cash_register_id", nullable = false)
    private Long cashRegisterId;

    @Column(name = "user_id", nullable = false)
    private Long userId;
}
