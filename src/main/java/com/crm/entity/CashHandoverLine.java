package com.crm.entity;

import com.crm.entity.enums.PaymentMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Topshirishning usul bo'yicha qatori (V86): topshirilmagan yozuvlar oqimi aniq {@link PaymentMethod} kesimida
 * (CASH_AND_CARD — CASH va CARD ga bo'linadi, eski BANK — TERMINAL ga). {@code actual} — Direktor kiritgan
 * (naqd: sanalgan; naqdsiz: terminal Z-hisobot / Click kabineti), {@code diff = actual − expected_net}.
 */
@Entity
@Table(name = "cash_handover_lines",
    uniqueConstraints = @UniqueConstraint(name = "ux_cash_handover_lines_method",
        columnNames = {"handover_id", "payment_method"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CashHandoverLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "handover_id", nullable = false)
    private Long handoverId;

    @Enumerated(value = EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    private PaymentMethod paymentMethod;

    @Column(name = "expected_in", nullable = false, precision = 15, scale = 2)
    private BigDecimal expectedIn;

    @Column(name = "expected_out", nullable = false, precision = 15, scale = 2)
    private BigDecimal expectedOut;

    @Column(name = "expected_net", nullable = false, precision = 15, scale = 2)
    private BigDecimal expectedNet;

    @Column(name = "actual", precision = 15, scale = 2)
    private BigDecimal actual;

    @Column(name = "diff", precision = 15, scale = 2)
    private BigDecimal diff;
}
