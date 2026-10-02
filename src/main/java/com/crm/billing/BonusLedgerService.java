package com.crm.billing;

import com.crm.entity.BalanceTransaction;
import com.crm.entity.BonusPenalty;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.BalanceTransactionType;
import com.crm.entity.enums.BonusPenaltyKind;
import com.crm.entity.enums.BonusPenaltyStatus;
import com.crm.entity.enums.BonusTargetType;
import com.crm.exception.CodedException;
import com.crm.exception.ConflictException;
import com.crm.repository.BalanceTransactionRepository;
import com.crm.repository.BonusPenaltyRepository;
import com.crm.repository.StudentGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * O'quvchi bonus/jarimasining ledger tomoni (§6.5):
 * <ul>
 *   <li>{@link #apply} — to'lovsiz qo'llash ({@code POST /api/bonus-penalties/{id}/apply}, SA/A);</li>
 *   <li>{@link #cancelApplied} — APPLIED → CANCELLED, faqat SA, sabab majburiy, {@code REVERSAL}.</li>
 * </ul>
 * To'lovdagi qo'llash — {@link PaymentBookingService#create}. Qulf: student → SG → bonus (§7.2);
 * PENDING sharti qulf ostida qayta tekshiriladi — ikki parallel qo'llash bittasi bo'lib qoladi.
 */
@Service
@RequiredArgsConstructor
public class BonusLedgerService {

    private final BillingLocks locks;
    private final LedgerService ledger;
    private final AccrualService accrualService;
    private final BillingSnapshotService snapshotService;
    private final BillingStatusService statusService;
    private final BillingGate gate;
    private final PaymentPlanner planner;
    private final BonusPenaltyRepository bonusPenaltyRepository;
    private final BalanceTransactionRepository transactionRepository;
    private final StudentGroupRepository studentGroupRepository;

    @Transactional
    public BonusPenalty apply(Long bonusId, Long groupId) {
        gate.requireWritable();
        BonusPenalty candidate = bonusPenaltyRepository.findById(bonusId)
            .orElseThrow(() -> CodedException.notFound("bonus.notFound", bonusId));
        if (candidate.getTargetType() != BonusTargetType.STUDENT || candidate.getStudent() == null) {
            throw CodedException.badRequest("bonus.apply.studentOnly");
        }
        Long studentId = candidate.getStudent().getId();
        StudentGroup sgCandidate = resolveEnrollment(candidate, groupId);

        BillingLocks.Locked locked = locks.acquire(BillingLocks.Plan.of()
            .student(studentId)
            .enrollment(sgCandidate.getId())
            .bonuses(List.of(bonusId)));
        BonusPenalty bp = locked.bonuses().get(0);
        if (bp.getStatus() != BonusPenaltyStatus.PENDING) {
            throw new ConflictException("bonus.notPending");
        }
        StudentGroup sg = locked.enrollment(sgCandidate.getId());
        LocalDate today = statusService.today();
        if (bp.getEffectiveDate() != null && bp.getEffectiveDate().isAfter(today)) {
            throw CodedException.badRequest("bonus.apply.future");
        }
        if (!Money.isWhole(bp.getAmount())) {
            throw CodedException.badRequest("money.wholeSumRequired");
        }

        accrualService.accrueLocked(sg, today);
        boolean isBonus = bp.getKind() == BonusPenaltyKind.BONUS;
        BalanceTransaction tx = ledger.post(LedgerService.Entry.builder()
            .enrollment(sg)
            .type(isBonus ? BalanceTransactionType.BONUS : BalanceTransactionType.PENALTY)
            .amount(isBonus ? bp.getAmount() : bp.getAmount().negate())
            .effectiveDate(isBonus || bp.getEffectiveDate() == null ? today : bp.getEffectiveDate())
            .referenceId(bp.getId())
            .note((isBonus ? "Bonus" : "Jarima") + (bp.getReason() != null ? ": " + bp.getReason() : ""))
            .build());

        bp.setStatus(BonusPenaltyStatus.APPLIED);
        bp.setLedgerTxId(tx.getId());
        bp.setStudentGroupId(sg.getId());
        bonusPenaltyRepository.save(bp);
        snapshotService.refresh(sg);
        return bp;
    }

    /** APPLIED STUDENT bonus/jarima → CANCELLED, REVERSAL bilan. Rol (SA) — chaqiruvchida. */
    @Transactional
    public BonusPenalty cancelApplied(Long bonusId, String reason) {
        gate.requireWritable();
        String why = reason != null ? reason.trim() : "";
        if (why.length() < 3 || why.length() > 500) {
            throw CodedException.badRequest("bonus.cancel.reasonRequired");
        }
        BonusPenalty candidate = bonusPenaltyRepository.findById(bonusId)
            .orElseThrow(() -> CodedException.notFound("bonus.notFound", bonusId));
        if (candidate.getTargetType() != BonusTargetType.STUDENT || candidate.getStudent() == null) {
            // O'qituvchi bonusi oylikka qo'llanadi — avval payroll bekor qilinadi (payroll-v2 §4)
            throw CodedException.badRequest("bonus.cancel.teacherApplied");
        }
        if (candidate.getLedgerTxId() == null) {
            // v1 da qo'llangan: ledger yozuvi yo'q — migratsiyadan keyin MANUAL_ADJUST bilan
            throw CodedException.badRequest("bonus.cancel.legacy");
        }
        BalanceTransaction original = transactionRepository.findById(candidate.getLedgerTxId())
            .orElseThrow(() -> CodedException.badRequest("bonus.cancel.legacy"));
        StudentGroup sgRef = original.getStudentGroup();

        BillingLocks.Locked locked = locks.acquire(BillingLocks.Plan.of()
            .student(candidate.getStudent().getId())
            .enrollment(sgRef.getId())
            .bonuses(List.of(bonusId)));
        BonusPenalty bp = locked.bonuses().get(0);
        if (bp.getStatus() != BonusPenaltyStatus.APPLIED) {
            throw new ConflictException("bonus.notApplied");
        }
        StudentGroup sg = locked.enrollment(sgRef.getId());

        ledger.reverse(original, "Bekor qilindi: " + (bp.getKind() == BonusPenaltyKind.BONUS ? "Bonus" : "Jarima")
            + " — " + why);
        bp.setStatus(BonusPenaltyStatus.CANCELLED);
        bp.setCancelReason(why);
        bonusPenaltyRepository.save(bp);
        snapshotService.refresh(sg);
        return bp;
    }

    /** Bonusga biriktirilgan SG, aks holda {@code groupId}, aks holda yagona ochiq SG (>1 → 400). */
    private StudentGroup resolveEnrollment(BonusPenalty bp, Long groupId) {
        Long studentId = bp.getStudent().getId();
        if (groupId == null && bp.getStudentGroupId() != null) {
            return studentGroupRepository.findById(bp.getStudentGroupId())
                .orElseThrow(() -> CodedException.notFound("payment.enrollment.notFound"));
        }
        return planner.resolveEnrollment(studentId, groupId);
    }
}
