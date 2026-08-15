package com.crm.service;

import com.crm.entity.BalanceTransaction;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.BalanceTransactionType;
import com.crm.repository.StudentGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bitta enrollment ledgerini ALOHIDA tranzaksiyada tekshiradi/tuzatadi.
 *
 * <p>Alohida bean — {@code REQUIRES_NEW} faqat proxy orqali chaqirilganda ishlaydi,
 * {@link MonthlyLedgerRepairService} ichidan o'z-o'ziga chaqirish bilan emas.
 * Shu sababli bitta o'quvchi yiqilsa qolganlari davom etadi.
 */
@Service
@RequiredArgsConstructor
public class MonthlyLedgerRepairWorker {

    private final StudentGroupRepository studentGroupRepository;
    private final BalanceExpectationService balanceExpectationService;
    private final BalanceTransactionService balanceTransactionService;

    /**
     * @param dryRun true — hech narsa yozilmaydi, faqat hisobot
     * @return hisobot qatori, enrollment topilmasa null
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map<String, Object> repairOne(Long studentGroupId, boolean dryRun) {
        StudentGroup sg = studentGroupRepository.findById(studentGroupId).orElse(null);
        if (sg == null) {
            return null;
        }

        BalanceExpectationService.Expectation exp = balanceExpectationService.compute(sg);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("studentGroupId", exp.studentGroupId());
        row.put("studentId", exp.studentId());
        row.put("studentName", exp.studentName());
        row.put("groupName", exp.groupName());
        row.put("currentBalance", exp.storedBalance());
        row.put("expectedBalance", exp.expectedBalance());
        row.put("diff", exp.diff());
        row.put("cashIn", exp.cashIn());
        row.put("periodCost", exp.periodCost());
        row.put("carriedLedger", exp.carriedLedger());
        row.put("ledgerSum", exp.ledgerSum());
        row.put("missingPeriodCharges", exp.missingPeriodCharges());
        row.put("wrongCredits", exp.wrongCredits());
        row.put("strayLessonCharges", exp.strayLessonCharges());
        row.put("legacyFreezeEntries", exp.legacyFreezeEntries());
        row.put("unlinkedPayments", exp.unlinkedPayments());
        row.put("hasIssue", exp.hasIssue());
        row.put("applied", false);

        if (dryRun || exp.diff().compareTo(BigDecimal.ZERO) == 0) {
            return row;
        }

        if (!exp.safeToApply()) {
            // Kutilgan balans to'liq emas — avtomatik tuzatish uni buzardi.
            row.put("skipped", true);
            row.put("skipReason", !exp.unlinkedPayments().isEmpty()
                ? "student_group_id bo'sh to'lov(lar) bor (" + exp.unlinkedPayments().size()
                    + " ta) — avval ularni enrollmentga bog'lang"
                : "MONTHLY guruhda UNFREEZE yozuvi bor — eski FREEZE ko'chirgan summani "
                    + "qaysi enrollmentga qaytarish kerakligi avtomatik aniqlanmaydi, "
                    + "qo'lda MANUAL_ADJUST qiling");
            return row;
        }

        // Eski yozuvlar O'ZGARTIRILMAYDI/O'CHIRILMAYDI — audit tarixi saqlanadi.
        // Farq bitta MANUAL_ADJUST bilan yopiladi; izoh prefiksi bo'yicha bu yozuv
        // keyingi qayta hisoblarda hisobga olinmaydi (takroriy ishga tushirish xavfsiz).
        BalanceTransaction tx = balanceTransactionService.record(
            sg,
            BalanceTransactionType.MANUAL_ADJUST,
            exp.diff(),
            null,
            buildNote(exp));

        row.put("applied", true);
        row.put("adjustmentTransactionId", tx.getId());
        row.put("adjustmentAmount", exp.diff());
        return row;
    }

    private static String buildNote(BalanceExpectationService.Expectation exp) {
        return BalanceExpectationService.REPAIR_NOTE_PREFIX
            + " MONTHLY daftar qayta qurildi: edi " + exp.storedBalance().toPlainString()
            + ", kutilgan " + exp.expectedBalance().toPlainString()
            + " | yetishmagan davr debeti: " + exp.missingPeriodCharges().size()
            + ", noto'g'ri kredit: " + exp.wrongCredits().size()
            + ", ortiqcha dars debeti: " + exp.strayLessonCharges().size()
            + ", xato muzlatish yozuvi: " + exp.legacyFreezeEntries().size()
            + " | kassa: " + exp.cashIn().toPlainString()
            + ", davrlar: " + exp.periodCost().toPlainString();
    }
}
