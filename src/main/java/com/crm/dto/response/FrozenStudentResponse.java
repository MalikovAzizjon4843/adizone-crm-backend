package com.crm.dto.response;

import com.crm.entity.enums.StudentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code GET /api/students/frozen} — bitta o'quvchi, ichida har muzlatilgan yozilma.
 *
 * <p>Summalar billing v2 ledger'idan ({@code balance_transactions}, Σ amount har SG bo'yicha) —
 * {@code students.balance} (barcha SG yig'indisi) yoki eski v1 ustunlaridan emas.
 * Eski maydonlar ({@code frozenDate}, {@code lastGroupId/Name}, {@code balance}) saqlangan:
 * eng oxirgi muzlatilgan yozilma va muzlatilganlar jami.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FrozenStudentResponse {
    private Long studentId;
    private String fullName;
    private String phone;
    private StudentStatus studentStatus;
    /** Eng oxirgi muzlatilgan yozilmaning {@code frozenFrom} i. */
    private LocalDate frozenDate;
    /** Σ muzlatilgan yozilmalar ledger balansi (ishorali). */
    private BigDecimal balance;
    private Long lastGroupId;
    private String lastGroupName;
    /** Σ max(0, balans) — o'quvchining muzlatilgan guruhlarda qolgan puli. */
    private BigDecimal totalRemaining;
    /** Σ max(0, −balans) — muzlatilgan guruhlardagi qarz. */
    private BigDecimal totalDebt;
    @Builder.Default
    private List<FrozenEnrollment> enrollments = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FrozenEnrollment {
        private Long studentGroupId;
        private Long groupId;
        private String groupName;
        /** {@code student_groups.frozen_from}; eski (v1) muzlatishda {@code leave_date}. */
        private LocalDate frozenFrom;
        /** Ledger balansi: Σ balance_transactions.amount (ishorali). */
        private BigDecimal balance;
        /** max(0, balance) — qolgan summa. */
        private BigDecimal remainingAmount;
        /** max(0, −balance). */
        private BigDecimal debt;
        private String note;
    }
}
