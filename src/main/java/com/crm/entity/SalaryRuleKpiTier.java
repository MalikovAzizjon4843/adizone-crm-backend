package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * KPI pog'onasi (payroll v3, V82/V88; ADMIN, SALES_HEAD): qoidaning {@code kpi_metric} qiymati
 * {@code > threshold} ({@code inclusive = false}) yoki {@code ≥ threshold} ({@code inclusive = true}) bo'lsa
 * {@code bonus}. Bir nechta pog'ona — eng yuqori bajarilgani olinadi, yig'ilmaydi.
 */
@Entity
@Table(name = "salary_rule_kpi_tiers",
    uniqueConstraints = @UniqueConstraint(name = "ux_salary_rule_kpi_tiers_rule_threshold",
        columnNames = {"salary_rule_id", "threshold"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SalaryRuleKpiTier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "salary_rule_id", nullable = false)
    private SalaryRule salaryRule;

    @Column(name = "threshold", nullable = false)
    private Integer threshold;

    @Column(name = "bonus", nullable = false, precision = 12, scale = 2)
    private BigDecimal bonus;

    /**
     * V88: true — shart "≥ threshold", false — "&gt; threshold" (V82 ma'nosi). Hech qachon null emas: boshlang'ich
     * false, getter null ni false deb o'qiydi; {@code @ColumnDefault} — Hibernate yaratgan sxemada ham DEFAULT false.
     */
    @Column(name = "inclusive", nullable = false)
    @org.hibernate.annotations.ColumnDefault(value = "false")
    @Builder.Default
    private Boolean inclusive = false;

    public boolean isInclusive() {
        return Boolean.TRUE.equals(inclusive);
    }

    @PrePersist
    @PreUpdate
    protected void defaults() {
        if (inclusive == null) {
            inclusive = false;
        }
    }
}
