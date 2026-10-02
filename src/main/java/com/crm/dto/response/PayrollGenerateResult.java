package com.crm.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * {@code POST /api/payroll/generate} natijasi (payroll-v2 §1.2).
 *
 * @param created      yangi DRAFT lar soni
 * @param recalculated qayta hisoblangan DRAFT lar soni ({@code recalculate=true})
 * @param skipped      tegilmagan xodimlar va sababi
 * @param totalAmount  yaratilgan + qayta hisoblangan DRAFT lar {@code netSalary} yig'indisi
 */
public record PayrollGenerateResult(
    int created,
    int recalculated,
    List<Skipped> skipped,
    BigDecimal totalAmount) {

    /**
     * {@code reason}: NOT_CALCULABLE | DRAFT_EXISTS | APPROVED | PAID | ERROR.
     * {@code code} — aniqroq sabab: NOT_CALCULABLE da RULE_NOT_FOUND, TEACHER_PROFILE_MISSING,
     * TEACHER_PROFILE_LINKED_TO_OTHER_USER, ROLE_NOT_CALCULATED; ERROR da DB_CONSTRAINT, UNEXPECTED.
     * {@code message} — o'qish uchun matn.
     */
    public record Skipped(Long userId, String fullName, String reason, String code, String message, Long payrollId) {
    }
}
