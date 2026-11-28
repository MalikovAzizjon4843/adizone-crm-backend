package com.crm.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Qo'lda ko'chirish (chiqarish TRANSFERRED + yangi guruhga qo'shish) — aniqlash, ta'mir va ogohlantirishlar.
 * {@code GET /api/admin/repair/manual-transfers}, {@code POST /api/admin/repair/merge-into-transfer},
 * {@code GET /api/students/{id}/add-to-group-check}.
 */
public final class ManualTransferDtos {

    private ManualTransferDtos() {
    }

    /** TRANSFER_OK — transfer oqimi (yoki ta'mirlangan); NO_LOSS — ko'chadigan pul yo'q; AFFECTED — ta'mir kerak; UNCLEAR — qo'lda ko'rish. */
    public enum Classification { TRANSFER_OK, NO_LOSS, AFFECTED, UNCLEAR }

    /**
     * Bitta juftlik (eski yozilma → yangi yozilma), o'quvchi bo'yicha vaqt tartibida.
     *
     * @param continuedAnchor transfer bo'lganda yangi yozilma hisob boshlanadigan sana (eski oxirgi davr oxiri + 1)
     * @param refundAmount    yangi yozilmaning shu sanadan oldin boshlangan davrlari (ikki marta hisob)
     * @param oldBalance      eski yozilmada qolgan balans (ko'chadi)
     * @param unusedAmount    yangi yozilmaga o'tadigan kredit = max(0, refundAmount + oldBalance)
     * @param currentDebt     o'quvchining hozirgi qarzi (faol yozilmalar bo'yicha)
     * @param chainKey        zanjir (A→B→C) — bir xil kalit; zanjirsiz — null
     */
    public record Row(Long studentId, String studentName, String admissionNumber,
                      Long oldEnrollmentId, Long oldGroupId, String oldGroupName, LocalDate oldJoinDate,
                      LocalDate oldExitDate,
                      Long newEnrollmentId, Long newGroupId, String newGroupName, LocalDate newJoinDate,
                      LocalDate newPaymentStartDate, boolean newActive,
                      LocalDate continuedAnchor, String mode, BigDecimal refundAmount, BigDecimal oldBalance,
                      BigDecimal unusedAmount, BigDecimal newBalance, BigDecimal currentDebt,
                      Classification classification, String reasonCode, String reason, String chainKey) {
    }

    public record Report(LocalDate from, LocalDate to, int pairs, Map<String, Long> counts, int affected,
                         BigDecimal affectedTotal, List<Row> rows) {
    }

    // ── ta'mir ──────────────────────────────────────────────────────────

    public record PeriodState(Long id, LocalDate start, LocalDate end, BigDecimal amount, BigDecimal refunded,
                              String status) {
    }

    /** Juftlik holati: eski/yangi yozilma balansi, o'quvchi balansi va qarzi, yangi yozilma langari va davrlari. */
    public record PairState(BigDecimal oldBalance, BigDecimal newBalance, BigDecimal studentBalance,
                            BigDecimal studentDebt, LocalDate newPaymentStartDate, List<PeriodState> newPeriods) {
    }

    /** {@code status}: APPLIED (dryRun'da — qo'llanadi), SKIPPED (sabab bilan), FAILED (xato). */
    public record RepairRow(Long studentId, String studentName, Long oldEnrollmentId, Long newEnrollmentId,
                            String status, String reasonCode, String reason, String mode,
                            LocalDate continuedAnchor, BigDecimal refundAmount, BigDecimal movedAmount,
                            BigDecimal credit, PairState before, PairState after) {
    }

    public record RepairResult(boolean dryRun, int requested, int applied, int skipped, int failed,
                               BigDecimal totalCredit, List<RepairRow> rows) {
    }

    // ── ogohlantirish ───────────────────────────────────────────────────

    /**
     * {@code USE_TRANSFER}: qo'lda o'tkazish o'rniga "Guruhga ko'chirish" ishlatilsin. Bloklamaydi.
     *
     * @param fromEnrollmentId eski (faol yoki yaqinda yopilgan) yozilma
     * @param toEnrollmentId   yangi yozilma (chiqarishda — o'quvchining boshqa faol yozilmasi)
     * @param unusedAmount     transfer'siz qilinsa ikki marta hisoblanadigan / ko'chmay qoladigan summa
     */
    public record TransferWarning(String code, Long fromEnrollmentId, Long fromGroupId, String fromGroupName,
                                  Long toEnrollmentId, Long toGroupId, String toGroupName, BigDecimal unusedAmount,
                                  LocalDate continuedAnchor, boolean fromActive, String message) {
    }

    public record AddToGroupCheck(Long studentId, Long groupId, List<TransferWarning> warnings) {
    }
}
