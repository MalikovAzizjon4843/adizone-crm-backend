package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "student_payment_plans")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StudentPaymentPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    private Group group;

    @Column(name = "plan_start_date", nullable = false)
    private LocalDate planStartDate;

    @Column(name = "plan_end_date")
    private LocalDate planEndDate;

    @Column(name = "monthly_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal monthlyAmount;

    @Column(name = "due_day")
    private Integer dueDay = 1;

    @Column(length = 20)
    private String status = "ACTIVE";

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
