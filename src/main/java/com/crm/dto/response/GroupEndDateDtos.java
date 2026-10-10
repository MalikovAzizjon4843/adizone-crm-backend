package com.crm.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Guruh tugash sanasi (billing-v2 R3, §14.3): {@code end_date} o'zgarishi ta'siri, o'zgargandan keyingi
 * hisob tiklanishi va diqqat talab qiladigan guruhlar.
 */
public final class GroupEndDateDtos {

    private GroupEndDateDtos() {
    }

    /** Yoziladigan davr: {@code amount} — PERIOD_CHARGE summasi (100% chegirmada 0). */
    public record Period(LocalDate start, LocalDate end, BigDecimal amount) {
    }

    /**
     * Bitta ochiq yozilma. {@code skipReason} — davr yozilmaydi: {@code HOLD}, {@code FROZEN}, {@code TRIAL},
     * {@code PER_LESSON}, {@code NO_ANCHOR}, {@code GROUP_STATUS}; null — accrual qoidalari bo'yicha hisoblandi.
     */
    public record ImpactRow(Long studentGroupId, Long studentId, String studentName, String skipReason,
                            List<Period> periods, BigDecimal charge,
                            BigDecimal balanceBefore, BigDecimal balanceAfter, BigDecimal debtAfter,
                            LocalDate debtSinceAfter, String statusBefore, String statusAfter,
                            boolean debtorBefore, boolean debtorAfter,
                            LocalDate nextPaymentDateBefore, LocalDate nextPaymentDateAfter,
                            BigDecimal nextPaymentAmountAfter) {
    }

    public record ImpactTotals(int enrollments, int affected, int periods, BigDecimal amount,
                               int debtorsBefore, int debtorsAfter, int held) {
    }

    /** {@code GET /api/groups/{id}/end-date-impact} — faqat o'qiydi. */
    public record Impact(Long groupId, String groupName, String status, LocalDate startDate,
                         LocalDate currentEndDate, LocalDate newEndDate, LocalDate today,
                         List<ImpactRow> enrollments, ImpactTotals totals) {
    }

    /** Guruh {@code end_date} / holati o'zgargandan keyin yozilgan davrlar (audit va javob uchun). */
    public record CatchUpRow(Long studentGroupId, Long studentId, List<Period> periods, BigDecimal charge) {
    }

    public record CatchUp(Long groupId, int enrollments, int periods, BigDecimal amount, List<CatchUpRow> rows) {
    }

    /**
     * Diqqat talab qiladigan guruh. {@code stoppedEnrollments} — R3 sababli davri yozilmay qolgan ochiq yozilmalar
     * (tugash sanasisiz hisoblansa bugungacha davr yozilardi), {@code missedPeriods / missedAmount} — ularning jami.
     */
    public record AttentionRow(Long groupId, String groupName, String status, LocalDate startDate, LocalDate endDate,
                               boolean endBeforeStart, int openEnrollments, int stoppedEnrollments,
                               int heldEnrollments, int missedPeriods, BigDecimal missedAmount) {
    }

    /** {@code GET /api/groups/attention}. */
    public record Attention(LocalDate today, int groups, int stoppedEnrollments, List<AttentionRow> rows) {
    }
}
