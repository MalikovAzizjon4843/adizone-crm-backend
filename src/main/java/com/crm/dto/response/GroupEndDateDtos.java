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

    /**
     * Yoziladigan davr: {@code amount} — PERIOD_CHARGE summasi (100% chegirmada 0). Oxirgi davr (guruh shu davrda
     * tugaydi) — {@code lessons} × {@code lessonPrice}; to'liq davrda ikkalasi null.
     */
    public record Period(LocalDate start, LocalDate end, BigDecimal amount, Integer lessons, BigDecimal lessonPrice) {
    }

    /**
     * Yozilgan oxirgi davr qayta hisobi (guruh tugash sanasi o'zgardi yoki qoida oldidan to'liq narx bilan yozilgan):
     * {@code diff > 0} — qo'shimcha PERIOD_CHARGE, {@code diff < 0} — PERIOD_REFUND.
     */
    public record Recalc(Long periodId, Long studentGroupId, LocalDate start, LocalDate end,
                         BigDecimal oldAmount, BigDecimal newAmount, BigDecimal diff,
                         Integer oldLessons, Integer newLessons, BigDecimal lessonPrice, Long chargeTxId) {
    }

    /**
     * Bitta ochiq yozilma. {@code skipReason} — davr yozilmaydi: {@code HOLD}, {@code FROZEN}, {@code TRIAL},
     * {@code PER_LESSON}, {@code NO_ANCHOR}, {@code GROUP_STATUS}; null — accrual qoidalari bo'yicha hisoblandi.
     */
    public record ImpactRow(Long studentGroupId, Long studentId, String studentName, String skipReason,
                            List<Period> periods, List<Recalc> recalculated, BigDecimal charge,
                            BigDecimal balanceBefore, BigDecimal balanceAfter, BigDecimal debtAfter,
                            LocalDate debtSinceAfter, String statusBefore, String statusAfter,
                            boolean debtorBefore, boolean debtorAfter,
                            LocalDate nextPaymentDateBefore, LocalDate nextPaymentDateAfter,
                            BigDecimal nextPaymentAmountAfter) {
    }

    public record ImpactTotals(int enrollments, int affected, int periods, int recalculated, BigDecimal amount,
                               int debtorsBefore, int debtorsAfter, int held) {
    }

    /** {@code GET /api/groups/{id}/end-date-impact} — faqat o'qiydi. */
    public record Impact(Long groupId, String groupName, String status, LocalDate startDate,
                         LocalDate currentEndDate, LocalDate newEndDate, LocalDate today,
                         List<ImpactRow> enrollments, ImpactTotals totals) {
    }

    /** Guruh {@code end_date} / holati o'zgargandan keyin yozilgan davrlar (audit va javob uchun). */
    public record CatchUpRow(Long studentGroupId, Long studentId, List<Period> periods, List<Recalc> recalculated,
                             BigDecimal charge) {
    }

    /** {@code amount} — yangi davrlar va qayta hisob farqlari yig'indisi (farq manfiy bo'lishi mumkin). */
    public record CatchUp(Long groupId, int enrollments, int periods, int recalculated, BigDecimal amount,
                          List<CatchUpRow> rows) {
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

    /** {@code POST /api/admin/repair/prorate-last-periods} — bitta davr. */
    public record RepairRow(Long studentGroupId, Long studentId, String studentName, Long groupId, String groupName,
                            LocalDate groupEndDate, Long periodId, LocalDate periodStart, LocalDate periodEnd,
                            BigDecimal oldAmount, BigDecimal newAmount, BigDecimal diff,
                            Integer oldLessons, Integer lessons, BigDecimal lessonPrice) {
    }

    /**
     * {@code dryRun = true} — hech narsa yozilmaydi ({@code rows} — qo'llanadigan farqlar); {@code false} — har yozilma
     * alohida tranzaksiyada qo'llangan ({@code rows} — qo'llanganlari, {@code failed} / {@code errors} — yiqilganlari).
     */
    public record Repair(boolean dryRun, int candidates, int enrollments, int periods, BigDecimal totalDiff,
                         int failed, List<RepairRow> rows, List<String> errors) {
    }
}
