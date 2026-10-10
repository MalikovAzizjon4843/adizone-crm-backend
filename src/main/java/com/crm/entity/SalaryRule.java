package com.crm.entity;

import com.crm.entity.enums.UserRole;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "salary_rules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SalaryRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private UserRole role;

    /** NULL = rol uchun umumiy qoida */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "base_salary", precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal fixedSalary = BigDecimal.ZERO;

    @Column(name = "per_student_fee", precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal perPayingStudent = BigDecimal.ZERO;

    @Column(name = "new_student_bonus", precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal perNewStudent = BigDecimal.ZERO;

    @Column(name = "kpi_threshold")
    private Integer kpiThreshold;

    @Column(name = "kpi_bonus", precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal kpiBonus = BigDecimal.ZERO;

    /**
     * TEACHER: bir o'tilgan o'rinbosar darsi uchun qat'iy summa (leaves-exams-contracts §3.3).
     * NULL — belgilanmagan: shaxsiy qoidada bo'lmasa rol qoidasidan olinadi.
     */
    @Column(name = "substitute_lesson_rate", precision = 12, scale = 2)
    private BigDecimal substituteLessonRate;

    /**
     * TEACHER (payroll v3, V82): INDIVIDUAL dars turidagi o'quvchi ulushi; {@code per_student_fee} — GROUP. Null —
     * belgilanmagan: o'qituvchida individual o'quvchi bo'lsa oylik hisoblanmaydi ({@code INDIVIDUAL_SHARE_MISSING}).
     */
    @Column(name = "per_student_individual", precision = 12, scale = 2)
    private BigDecimal perStudentIndividual;

    /**
     * Stavka (payroll v3, V82): hisobdagi fiksa = {@code base_salary × employment_rate}; 0 &lt; stavka ≤ 1. Hech qachon
     * null emas: boshlang'ich 1.00, setter va @PrePersist/@PreUpdate null ni 1.00 ga aylantiradi; {@code @ColumnDefault}
     * — Hibernate yaratgan sxemada (testlar, ddl-auto) ham V82 dagi kabi DEFAULT 1.00 bo'lsin (xom INSERT uchun).
     */
    @Column(name = "employment_rate", precision = 3, scale = 2, nullable = false)
    @org.hibernate.annotations.ColumnDefault("1.00")
    @Builder.Default
    private BigDecimal employmentRate = BigDecimal.ONE;

    /** ADMIN KPI pog'onalari (payroll v3, V82): shart qat'iy "faol > threshold", eng yuqori bajarilgani olinadi. */
    @OneToMany(mappedBy = "salaryRule", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("threshold ASC")
    @Builder.Default
    private java.util.List<SalaryRuleKpiTier> kpiTiers = new java.util.ArrayList<>();

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    /**
     * Shu sanagacha (kiritilgan) amal qiladi; null — muddatsiz (payroll-v2 §11 #3). Shu
     * xodim/rol uchun yangi qoida yaratilganda oldingisiga avtomatik {@code yangi.effectiveFrom − 1}.
     */
    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
        if (isActive == null) {
            isActive = true;
        }
        if (employmentRate == null) {
            employmentRate = BigDecimal.ONE;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        if (employmentRate == null) {
            employmentRate = BigDecimal.ONE;
        }
    }

    /** null — 1.00 (to'liq stavka): NOT NULL ustunga null yetib bormasin. */
    public void setEmploymentRate(BigDecimal employmentRate) {
        this.employmentRate = employmentRate != null ? employmentRate : BigDecimal.ONE;
    }

    /** {@code @Builder.Default} no-args konstruktorga ham tegishli bo'lishi Lombok versiyasiga bog'liq — aniq qilib. */
    public BigDecimal getEmploymentRate() {
        return employmentRate != null ? employmentRate : BigDecimal.ONE;
    }
}
