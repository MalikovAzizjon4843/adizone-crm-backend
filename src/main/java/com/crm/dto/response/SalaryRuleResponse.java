package com.crm.dto.response;

import com.crm.entity.enums.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Oylik qoidasi — nomlar {@link com.crm.dto.request.SalaryRuleRequest} bilan bir xil (payroll-v2 §7). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalaryRuleResponse {
    private Long id;
    private UserRole role;
    private Long userId;
    private String userName;
    private BigDecimal fixedSalary;
    private BigDecimal perPayingStudent;
    private BigDecimal perNewStudent;
    private Integer kpiThreshold;
    private BigDecimal kpiBonus;
    private BigDecimal substituteLessonRate;
    /** v3: INDIVIDUAL o'quvchi ulushi (TEACHER). */
    private BigDecimal perStudentIndividual;
    /** v3: stavka (fiksa × stavka). */
    private BigDecimal employmentRate;
    /** v3 (V88): KPI metrikasi — ACTIVE_STUDENTS | NEW_STUDENTS. */
    private com.crm.entity.enums.KpiMetric kpiMetric;
    /**
     * v3: KPI pog'onalari ("metrika &gt; threshold", {@code inclusive} — "≥"); kpiThreshold/kpiBonus — eski ("≥")
     * ko'rinish, eng past pog'ona (faqat ADMIN + ACTIVE_STUDENTS; aks holda null / 0).
     */
    private java.util.List<KpiTier> kpiTiers;
    private Boolean isActive;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KpiTier {
        private Integer threshold;
        private BigDecimal bonus;
        /** V88: true — "≥ threshold", false — "&gt; threshold". */
        private boolean inclusive;
    }
}
