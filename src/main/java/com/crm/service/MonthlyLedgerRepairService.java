package com.crm.service;

import com.crm.audit.AuditAction;
import com.crm.audit.AuditContext;
import com.crm.audit.Audited;
import com.crm.entity.enums.PaymentType;
import com.crm.repository.StudentGroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MONTHLY enrollmentlarning balans daftarini qayta quradi.
 *
 * <p>Sabab: ilgari MONTHLY to'lovda ledgerga faqat KREDIT yozilardi, sotib olingan
 * davr uchun DEBET yozilmasdi. Natijada balans to'langan pulning butun summasiga
 * teng bo'lib o'sib ketgan. Bundan tashqari davomat MONTHLY o'quvchidan ham
 * {@code monthlyFee/8} yechardi (LESSON_CHARGE) — bular ham bekor qilinishi kerak.
 *
 * <p>Ataylab MUHIM: metod eski yozuvlarni o'zgartirmaydi va o'chirmaydi. Farq
 * har bir enrollment uchun bitta MANUAL_ADJUST yozuvi bilan yopiladi, izohida
 * sababi bilan. Shu sababli audit tarixi buzilmaydi.
 *
 * <p>Har bir enrollment alohida tranzaksiyada ({@link MonthlyLedgerRepairWorker}) —
 * bittasi yiqilsa qolganlari davom etadi, yiqilganlari {@code failed} ro'yxatiga tushadi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MonthlyLedgerRepairService {

    private final StudentGroupRepository studentGroupRepository;
    private final MonthlyLedgerRepairWorker worker;

    /**
     * @param dryRun true (DEFAULT) — hech narsa yozilmaydi, faqat hisobot qaytariladi
     */
    @Audited(action = AuditAction.REPAIR, entity = "Balance",
        summary = "'MONTHLY daftar qayta qurildi'")
    public Map<String, Object> rebuildMonthlyLedger(boolean dryRun) {
        if (dryRun) {
            // Tahlil rejimi hech narsani o'zgartirmaydi — kuzatishga arzimaydi
            AuditContext.skip();
        }
        List<Long> ids = studentGroupRepository.findIdsByPaymentTypeOrNull(PaymentType.MONTHLY);

        int checked = 0;
        int withIssues = 0;
        int applied = 0;
        int skipped = 0;
        BigDecimal totalDiff = BigDecimal.ZERO;
        List<Map<String, Object>> details = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();

        for (Long sgId : ids) {
            checked++;
            try {
                Map<String, Object> row = worker.repairOne(sgId, dryRun);
                if (row == null) {
                    continue;
                }
                if (Boolean.TRUE.equals(row.get("hasIssue"))) {
                    withIssues++;
                    totalDiff = totalDiff.add((BigDecimal) row.get("diff"));
                    details.add(row);
                }
                if (Boolean.TRUE.equals(row.get("applied"))) {
                    applied++;
                }
                if (Boolean.TRUE.equals(row.get("skipped"))) {
                    skipped++;
                }
            } catch (RuntimeException e) {
                log.error("rebuild-monthly-ledger failed for studentGroup={}: {}",
                    sgId, e.getMessage(), e);
                Map<String, Object> fail = new LinkedHashMap<>();
                fail.put("studentGroupId", sgId);
                fail.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                failed.add(fail);
            }
        }

        log.info("rebuild-monthly-ledger dryRun={} checked={} withIssues={} applied={} "
                + "skipped={} failed={} totalDiff={}",
            dryRun, checked, withIssues, applied, skipped, failed.size(), totalDiff);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dryRun", dryRun);
        result.put("checked", checked);
        result.put("withIssues", withIssues);
        result.put("applied", applied);
        result.put("skipped", skipped);
        result.put("totalDiff", totalDiff);
        result.put("failedCount", failed.size());
        result.put("failed", failed);
        result.put("details", details);
        return result;
    }
}
