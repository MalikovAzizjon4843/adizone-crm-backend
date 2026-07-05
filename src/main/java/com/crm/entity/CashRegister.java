package com.crm.entity;

import com.crm.entity.enums.CashRegisterStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "cash_registers")
@Getter
@Setter
@NoArgsConstructor
public class CashRegister {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, updatable = false, length = 36)
    private String uuid;

    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "moderator_id")
    private User moderator;

    @Column(name = "plastic_balance", precision = 15, scale = 2)
    private BigDecimal plasticBalance = BigDecimal.ZERO;

    @Column(name = "cash_balance", precision = 15, scale = 2)
    private BigDecimal cashBalance = BigDecimal.ZERO;

    @Column(precision = 15, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CashRegisterStatus status = CashRegisterStatus.ACTIVE;

    @Column(name = "accept_online_payment")
    private boolean acceptOnlinePayment = false;

    private boolean archived = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
        }
        if (plasticBalance == null) {
            plasticBalance = BigDecimal.ZERO;
        }
        if (cashBalance == null) {
            cashBalance = BigDecimal.ZERO;
        }
        if (balance == null) {
            balance = BigDecimal.ZERO;
        }
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
