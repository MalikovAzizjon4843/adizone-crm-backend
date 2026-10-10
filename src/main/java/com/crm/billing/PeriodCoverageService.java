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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

    /**
     * Payroll v2 §11 #7: yopilishida kamida bitta real {@code PAYMENT} krediti qatnashgan
     * majburiyatlar ({@code charge_tx_id} lar). Faqat DISCOUNT/BONUS/MANUAL_ADJUST bilan to'liq
     * yopilgani bu to'plamga kirmaydi. Ledgerga tegmaydi.
     */
    public Set<Long> paymentCoveredCharges(Long studentGroupId) {
        List<BalanceTransaction> ledgerRows = transactionRepository.findLedgerForFifo(studentGroupId);
        Map<Long, BalanceTransactionType> types = new HashMap<>();
        List<PeriodCoverage.Line> lines = new ArrayList<>();
        for (BalanceTransaction t : ledgerRows) {
            types.put(t.getId(), t.getType());
            lines.add(new PeriodCoverage.Line(t.getId(), t.getAmount(), t.getEffectiveDate(),
                t.getRelatedTxId(), t.getCreatedAt(), isNeutral(t)));
        }
        Set<Long> out = new HashSet<>();
        PeriodCoverage.contributors(lines).forEach((obligationId, credits) -> {
            if (credits.stream().anyMatch(id -> types.get(id) == BalanceTransactionType.PAYMENT)) {
                out.add(obligationId);
            }
        });
        return out;
    }

    /** Majburiyatni (qisman) yopgan kredit qismi: kredit sanasi va summasi. */
    public record Share(LocalDate creditDate, BigDecimal amount) {
    }

    /**
     * Real pul sifatida ulush beradigan kreditlar (payroll v3): {@code PAYMENT} va {@code TRANSFER_IN} (guruhdan guruhga
     * ko'chganda eski yozilmadan o'tgan to'langan balans — qism sanasi o'tkazma kuni). Eski yozilmada o'sha pul
     * TRANSFER_OUT majburiyatini yopadi, u davr emas — ikki marta sanalmaydi.
     */
    private static final Set<BalanceTransactionType> REAL_MONEY =
        java.util.EnumSet.of(BalanceTransactionType.PAYMENT, BalanceTransactionType.TRANSFER_IN);

    /**
     * Payroll v3 (chala to'lov): har majburiyat ({@code charge_tx_id} / LESSON_CHARGE id) uchun FIFO bo'yicha uni
     * yopgan kredit qismlari va majburiyatning net summasi (qaytarim / qayta hisob bilan). {@code anyCredit = false}
     * — faqat real pul qismlari ({@link #REAL_MONEY}); DISCOUNT, BONUS, MANUAL_ADJUST ulush bermaydi. Ledgerga tegmaydi.
     */
    public record PaymentShares(Map<Long, BigDecimal> obligationNet, Map<Long, List<Share>> shares) {

        public List<Share> of(Long obligationId) {
            return shares.getOrDefault(obligationId, List.of());
        }

        public BigDecimal netOf(Long obligationId) {
            return obligationNet.getOrDefault(obligationId, BigDecimal.ZERO);
        }
    }

    public PaymentShares paymentShares(Long studentGroupId, boolean anyCredit) {
        Map<Long, BalanceTransactionType> types = new HashMap<>();
        List<PeriodCoverage.Line> lines = new ArrayList<>();
        for (BalanceTransaction t : transactionRepository.findLedgerForFifo(studentGroupId)) {
            types.put(t.getId(), t.getType());
            lines.add(new PeriodCoverage.Line(t.getId(), t.getAmount(), t.getEffectiveDate(),
                t.getRelatedTxId(), t.getCreatedAt(), isNeutral(t)));
        }
        PeriodCoverage.Allocation allocation = PeriodCoverage.allocation(lines);
        Map<Long, List<Share>> shares = new HashMap<>();
        for (PeriodCoverage.Part p : allocation.parts()) {
            if (anyCredit || REAL_MONEY.contains(types.get(p.creditId()))) {
                shares.computeIfAbsent(p.obligationId(), k -> new ArrayList<>()).add(new Share(p.creditDate(), p.amount()));
            }
        }
        return new PaymentShares(allocation.obligations(), shares);
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
