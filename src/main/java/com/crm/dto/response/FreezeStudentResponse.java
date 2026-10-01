package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FreezeStudentResponse {
    private Long studentId;
    private BigDecimal totalBalance;

    @Builder.Default
    private List<FrozenGroupBreakdown> groups = new ArrayList<>();

    // ── Billing v2 (§6.7): preview va freeze bir xil FreezePlan dan ──
    private java.time.LocalDate freezeDate;
    private Long studentGroupId;
    /** Yoziladigan (preview) / yozilgan (freeze) qatorlar: kerak bo'lsa PERIOD_CHARGE (pending), PERIOD_REFUND. */
    @Builder.Default
    private List<BillingLineDto> refundLines = new ArrayList<>();
    private BigDecimal refundTotal;
    private BigDecimal balanceBefore;
    private BigDecimal balanceAfter;
    private BigDecimal debtAfter;
    private String statusAfter;
    /** Muzlatishdan keyin o'quvchi holati: barcha faol guruhlari muzlatilsa FROZEN, aks holda ACTIVE. */
    private String studentStatusAfter;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FrozenGroupBreakdown {
        private Long groupId;
        private String groupName;
        /** O'qilgan (PRESENT/ABSENT/LATE) darslar */
        private Integer lessonsAttended;
        /** Alias: lessonsAttended */
        private int lessonsUsed;
        private BigDecimal lessonPrice;
        private BigDecimal used;
        private BigDecimal paid;
        private BigDecimal balance;
    }
}
