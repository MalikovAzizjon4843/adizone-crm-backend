package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * ADMIN KPI pog'onasi (payroll v3, V82): oy oxiridagi faol o'quvchilar {@code > threshold} bo'lsa {@code bonus}.
 * Bir nechta pog'ona — eng yuqori bajarilgani olinadi, yig'ilmaydi.
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
}
