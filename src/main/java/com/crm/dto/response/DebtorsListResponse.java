package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code GET /api/payments/debtors} — billing v2 yagona ta'rifi (docs/design/billing-v2.md §4.5).
 * Ro'yxatda faqat OVERDUE o'quvchilar. Eski maydonlar (totalDebt, amount,
 * monthsUnpaid, nextPaymentDate, groupName) eski front uchun saqlangan.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DebtorsListResponse {
    private long totalDebtors;
    private long overdue7Plus;
    private BigDecimal totalDebt;
    /** ACTIVE (default) yoki ALL. */
    private String scope;
    private int page;
    private int size;
    @Builder.Default
    private List<DebtorStudent> students = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DebtorStudent {
        private Long studentId;
        private String fullName;
        private String phone;
        /** Σ max(0, −sg.balance) — OVERDUE va PENDING SG lar. */
        private BigDecimal debt;
        /** Eng eski OVERDUE SG ning debtSince'i. */
        private LocalDate debtSince;
        private long daysOverdue;
        private String status;
        /** Σ c(sg) — faol MONTHLY SG lar (eski {@code monthlyAmount}). */
        private BigDecimal monthlyAmount;
        @Builder.Default
        private List<DebtorGroup> groups = new ArrayList<>();

        // ── eski front mosligi ──
        /** = debt */
        private BigDecimal totalDebt;
        /** = monthlyAmount */
        private BigDecimal amount;
        /** = ceil(debt / monthlyAmount); monthlyAmount 0 bo'lsa 1. */
        private long monthsUnpaid;
        /** = debtSince */
        private LocalDate nextPaymentDate;
        /** Birinchi (eng eski qarzli) guruh nomi. */
        private String groupName;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DebtorGroup {
        private Long studentGroupId;
        private Long groupId;
        private String groupName;
        private BigDecimal debt;
        private LocalDate debtSince;
        private long daysOverdue;
        private String status;
        private boolean closed;
    }
}
