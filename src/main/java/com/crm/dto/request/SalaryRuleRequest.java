package com.crm.dto.request;

import com.crm.entity.enums.KpiMetric;
import com.crm.entity.enums.UserRole;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Oylik qoidasi — payroll-v2 §7. Maydon nomlari javob bilan BIR XIL.
 *
 * <p>Noma'lum maydon (masalan eski {@code baseSalary}, {@code perStudentFee},
 * {@code newStudentBonus}) jimgina tashlab yuborilmaydi: {@link #getUnknownFields()}
 * bo'sh bo'lmasa servis 400 {@code salaryRule.field.unknown} qaytaradi — aks holda qoida
 * 0 so'm bilan saqlanib qolardi.
 */
@Data
public class SalaryRuleRequest {

    @NotNull(message = "{salaryRule.role.required}")
    private UserRole role;

    /** null — rol uchun umumiy qoida. */
    private Long userId;

    @DecimalMin(value = "0", message = "{salaryRule.amount.min}")
    private BigDecimal fixedSalary;

    /** TEACHER: har bir to'lagan o'quvchi (davr) uchun. */
    @DecimalMin(value = "0", message = "{salaryRule.amount.min}")
    private BigDecimal perPayingStudent;

    /** ADMIN, SALES_MANAGER, SALES_HEAD: har bir yangi o'quvchi uchun. */
    @DecimalMin(value = "0", message = "{salaryRule.amount.min}")
    private BigDecimal perNewStudent;

    /** ADMIN: oy oxirida faol o'quvchilar chegarasi. */
    @Min(value = 0, message = "{salaryRule.amount.min}")
    private Integer kpiThreshold;

    @DecimalMin(value = "0", message = "{salaryRule.amount.min}")
    private BigDecimal kpiBonus;

    /** TEACHER: bir o'tilgan o'rinbosar darsi uchun (leaves-exams-contracts §3.3); null — rol qoidasidan. */
    @DecimalMin(value = "0", message = "{salaryRule.amount.min}")
    private BigDecimal substituteLessonRate;

    /**
     * TEACHER (payroll v3): INDIVIDUAL dars turidagi o'quvchi ulushi; {@code perPayingStudent} — GROUP. null —
     * belgilanmagan (individual o'quvchi bo'lsa oylik hisoblanmaydi: {@code INDIVIDUAL_SHARE_MISSING}).
     */
    @DecimalMin(value = "0", message = "{salaryRule.amount.min}")
    private BigDecimal perStudentIndividual;

    /** Stavka (payroll v3): 0 &lt; stavka ≤ 1, standart 1.00; hisobdagi fiksa = fixedSalary × stavka. */
    private BigDecimal employmentRate;

    /**
     * KPI metrikasi (payroll v3, V88; ADMIN, SALES_HEAD): ACTIVE_STUDENTS (oy oxiridagi faol o'quvchilar) yoki
     * NEW_STUDENTS (oy davomida markazga kelgan yangi o'quvchilar). null — ACTIVE_STUDENTS. Boshqa rolda
     * NEW_STUDENTS — 400 {@code salaryRule.field.notApplicable}.
     */
    private KpiMetric kpiMetric;

    /**
     * KPI pog'onalari (payroll v3; ADMIN, SALES_HEAD): har biri {@code {threshold, bonus, inclusive}}, shart
     * "metrika &gt; threshold" ({@code inclusive} false/null) yoki "metrika ≥ threshold" ({@code inclusive = true}),
     * eng yuqori bajarilgani olinadi. Berilmasa va {@code kpiThreshold} berilsa (faqat ADMIN) — eski ma'no ("≥")
     * bitta pog'ona {@code threshold − 1} ga aylanadi (V82 bilan bir xil).
     */
    @Valid
    private List<KpiTier> kpiTiers;

    @Data
    public static class KpiTier {
        @NotNull(message = "{salaryRule.kpiTiers.invalid}")
        @Min(value = 0, message = "{salaryRule.amount.min}")
        private Integer threshold;

        @NotNull(message = "{salaryRule.kpiTiers.invalid}")
        @DecimalMin(value = "0", message = "{salaryRule.amount.min}")
        private BigDecimal bonus;

        /** V88: true — "≥ threshold"; false yoki null — "&gt; threshold". */
        private Boolean inclusive;
    }

    private Boolean isActive;

    private LocalDate effectiveFrom;

    /** Shu sanagacha (kiritilgan); null — muddatsiz. Yangi qoida yaratilganda oldingisiga avtomatik qo'yiladi. */
    private LocalDate effectiveTo;

    @JsonIgnore
    private final List<String> unknownFields = new ArrayList<>();

    @JsonAnySetter
    public void unknownField(String name, Object value) {
        unknownFields.add(name);
    }
}
