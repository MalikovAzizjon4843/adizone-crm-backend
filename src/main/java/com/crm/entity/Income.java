package com.crm.entity;

import com.crm.entity.enums.IncomeCategory;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "income")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Income {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @UuidGenerator
    @Column(unique = true, nullable = false, updatable = false)
    private UUID uuid;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IncomeCategory category;
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id")
    private Payment payment;
    @Column(length = 500)
    private String description;
    @Column(name = "income_date", nullable = false)
    private LocalDate incomeDate;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "received_by")
    private User receivedBy;
    @Column(columnDefinition = "TEXT")
    private String notes;
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    @PrePersist protected void onCreate() { createdAt = LocalDateTime.now(); updatedAt = LocalDateTime.now(); }
    @PreUpdate protected void onUpdate() { updatedAt = LocalDateTime.now(); }
}
