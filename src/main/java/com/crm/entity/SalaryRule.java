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
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
