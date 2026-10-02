package com.crm.billing;

import com.crm.entity.BalanceTransaction;
import com.crm.entity.BillingPeriod;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.BalanceTransactionType;
import com.crm.entity.enums.BillingPeriodStatus;
import com.crm.repository.BalanceTransactionRepository;
import com.crm.repository.BillingPeriodRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code billing_periods.due_date / grace_until / paid_on / paid_at / paid_tx_id} ni ledgerdan
 * yangilaydi (director-dashboard §3.2). {@link BillingSnapshotService} har SG yangilanishida
 * chaqiradi — shu tranzaksiyada; faqat o'zgargan qatorlar yoziladi. Ledgerga tegmaydi.
 */
@Service
@RequiredArgsConstructor
public class PeriodCoverageService {

    public static final String SOURCE_FIFO = "FIFO";
    public static final String SOURCE_MIGRATION = "MIGRATION_REPLAY";

    private final BalanceTransactionRepository transactionRepository;
    private final BillingPeriodRepository periodRepository;
    private final BillingProperties properties;

    /** @return o'zgargan davrlar soni */
    public int refresh(StudentGroup sg) {
        return refresh(sg, true);
    }

    /** Yozmasdan: nechta davr o'zgarardi (backfill dry-run, director-dashboard §2.2 G5). */
    public int diff(StudentGroup sg) {
        return refresh(sg, false);
    }

    private int refresh(StudentGroup sg, boolean apply) {
        if (sg == null || sg.getId() == null) {
            return 0;
        }
        List<BillingPeriod> periods = periodRepository.findByStudentGroupIdOrderByPeriodStartAsc(sg.getId());
        if (periods.isEmpty()) {
            return 0;
        }
        List<PeriodCoverage.Line> lines = new ArrayList<>();
        for (BalanceTransaction t : transactionRepository.findLedgerForFifo(sg.getId())) {
            lines.add(new PeriodCoverage.Line(t.getId(), t.getAmount(), t.getEffectiveDate(),
                t.getRelatedTxId(), t.getCreatedAt(), isNeutral(t)));
        }
        Map<Long, PeriodCoverage.Covered> covered = PeriodCoverage.compute(lines);

        int changed = 0;
        for (BillingPeriod p : periods) {
            // Avval hisob, keyin (apply bo'lsa) yozish — dry-run entity'ga umuman tegmaydi
            boolean fillDue = p.getDueDate() == null;
            boolean fillGrace = p.getGraceUntil() == null;
            PeriodCoverage.Covered c = isCollectible(p) ? covered.get(p.getChargeTxId()) : null;
            String source = c == null ? null : (p.getMigrationRunId() != null ? SOURCE_MIGRATION : SOURCE_FIFO);
            boolean coverageChanged = !Objects.equals(p.getPaidOn(), c != null ? c.paidOn() : null)
                || !Objects.equals(p.getPaidTxId(), c != null ? c.paidTxId() : null)
                || !Objects.equals(p.getCoverageSource(), source);
            if (!fillDue && !fillGrace && !coverageChanged) {
                continue;
            }
            changed++;
            if (!apply) {
                continue;
            }
            if (fillDue) {
                p.setDueDate(p.getPeriodStart());
            }
            if (fillGrace) {
                p.setGraceUntil(p.getPeriodStart().plusDays(properties.getGraceDays()));
            }
            if (coverageChanged) {
                p.setPaidOn(c != null ? c.paidOn() : null);
                p.setPaidAt(c != null ? c.paidAt() : null);
                p.setPaidTxId(c != null ? c.paidTxId() : null);
                p.setCoverageSource(source);
            }
            periodRepository.save(p);
        }
        return changed;
    }

    /** Muddatli majburiyat: yozilgan, qaytarilmagan, summasi bor (§1.2). */
    static boolean isCollectible(BillingPeriod p) {
        return (p.getStatus() == BillingPeriodStatus.CHARGED || p.getStatus() == BillingPeriodStatus.PARTIALLY_REFUNDED)
            && p.getChargeTxId() != null
            && Money.nz(p.getAmount()).subtract(Money.nz(p.getRefundedAmount())).signum() > 0;
    }

    /** §3.2.2 qadam 2: yig'indisi nol bo'lgan migratsiya juftligi replay'dan chiqariladi. */
    static boolean isNeutral(BalanceTransaction t) {
        if (t.getType() == BalanceTransactionType.MIGRATION) {
            return true;
        }
        if (t.getType() == BalanceTransactionType.PERIOD_CHARGE && t.getBillingPeriodId() == null) {
            return true;
        }
        return t.getType() == BalanceTransactionType.MANUAL_ADJUST && t.getNote() != null
            && t.getNote().startsWith(MigrationPlanner.LEDGER_REPAIR_PREFIX);
    }
}
