package com.crm.billing;

import com.crm.audit.AuditAction;
import com.crm.audit.Audited;
import com.crm.dto.request.RefundPayoutRequest;
import com.crm.dto.response.RefundPayoutResponse;
import com.crm.entity.BalanceTransaction;
import com.crm.entity.CashRegister;
import com.crm.entity.CashTransaction;
import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.BalanceTransactionType;
import com.crm.entity.enums.CashRegisterStatus;
import com.crm.entity.enums.PaymentMethod;
import com.crm.exception.CodedException;
import com.crm.repository.BalanceTransactionRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.service.CashRegisterService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * O'quvchiga pul qaytarish — {@code REFUND_PAYOUT} (§13 #24 qarori, v2 da).
 *
 * <ul>
 *   <li>Faqat SUPER_ADMIN, ADMIN (controller).</li>
 *   <li>Sabab majburiy.</li>
 *   <li>Kassa chiqimi bilan — bitta tranzaksiyada (I5 ruhida: pul kassadan chiqdi).</li>
 *   <li>Faqat musbat balans doirasida: SG balansidan ko'p qaytarib bo'lmaydi.</li>
 * </ul>
 * Qulf: student → SG → kassa (§7.2).
 */
@Service
@RequiredArgsConstructor
public class RefundPayoutService {

    private final BillingLocks locks;
    private final LedgerService ledger;
    private final BillingSnapshotService snapshotService;
    private final BillingStatusService statusService;
    private final BillingGate gate;
    private final StudentGroupRepository studentGroupRepository;
    private final BalanceTransactionRepository transactionRepository;
    private final CashRegisterService cashRegisterService;

    @Transactional
    @Audited(action = AuditAction.REFUND, entity = "Student",
        summary = "'Pul qaytarildi: ' + #result.amount + ' (' + #result.reason + ')'",
        entityId = "#studentId",
        label = "#result.studentName")
    public RefundPayoutResponse payout(Long studentId, RefundPayoutRequest r) {
        gate.requireWritable();
        if (r.getAmount() == null || r.getAmount().signum() <= 0) {
            throw CodedException.badRequest("refund.amount.required");
        }
        if (!Money.isWhole(r.getAmount()) || !Money.isWhole(r.getCashPart()) || !Money.isWhole(r.getCardPart())) {
            throw CodedException.badRequest("money.wholeSumRequired");
        }
        String reason = r.getReason() != null ? r.getReason().trim() : "";
        if (reason.length() < 3) {
            throw CodedException.badRequest("refund.reason.required");
        }

        StudentGroup candidate = resolve(studentId, r.getGroupId());
        BillingLocks.Locked locked = locks.acquire(BillingLocks.Plan.of()
            .student(studentId).enrollment(candidate.getId()).cashRegister(r.getCashRegisterId()));
        StudentGroup sg = locked.enrollment(candidate.getId());
        CashRegister register = locked.cashRegister(r.getCashRegisterId());
        if (register.isArchived() || register.getStatus() == CashRegisterStatus.ARCHIVED) {
            throw CodedException.badRequest("payment.cashRegister.archived");
        }

        BigDecimal balance = transactionRepository.sumAmountByStudentGroupId(sg.getId());
        if (r.getAmount().compareTo(Money.nz(balance)) > 0) {
            throw CodedException.badRequest("refund.amount.exceedsBalance", Money.normalize(Money.nz(balance)));
        }

        Student student = sg.getStudent();
        PaymentMethod method = r.getPaymentMethod() != null ? r.getPaymentMethod() : PaymentMethod.CASH;
        CashTransaction cash = cashRegisterService.recordExpense(
            register.getId(), r.getAmount(), method, "O'quvchiga pul qaytarildi", reason,
            statusService.today(), null, student, null, null, null, r.getCashPart(), r.getCardPart());

        BalanceTransaction tx = ledger.post(LedgerService.Entry.builder()
            .enrollment(sg)
            .type(BalanceTransactionType.REFUND_PAYOUT)
            .amount(r.getAmount().negate())
            .effectiveDate(statusService.today())
            .referenceId(cash.getId())
            .note("Qaytarildi: " + reason)
            .build());

        BillingSnapshot after = snapshotService.refresh(sg);
        return RefundPayoutResponse.builder()
            .studentId(student.getId())
            .studentName(student.getFirstName() + " " + student.getLastName())
            .groupId(sg.getGroup() != null ? sg.getGroup().getId() : null)
            .studentGroupId(sg.getId())
            .amount(Money.normalize(r.getAmount()))
            .reason(reason)
            .cashTransactionId(cash.getId())
            .line(PaymentBookingService.toLine(tx))
            .balanceAfter(after.balance())
            .debtAfter(after.debt())
            .statusAfter(after.status())
            .build();
    }

    /** {@code groupId} berilmasa — musbat balansli yagona SG. */
    private StudentGroup resolve(Long studentId, Long groupId) {
        List<StudentGroup> all = studentGroupRepository.findByStudentId(studentId);
        if (all.isEmpty()) {
            throw CodedException.notFound("error.student.notFound", studentId);
        }
        if (groupId != null) {
            return all.stream()
                .filter(sg -> sg.getGroup() != null && groupId.equals(sg.getGroup().getId()))
                .max((a, b) -> Money.nz(a.getBalance()).compareTo(Money.nz(b.getBalance())))
                .orElseThrow(() -> CodedException.notFound("payment.enrollment.notFound"));
        }
        List<StudentGroup> positive = all.stream().filter(sg -> Money.nz(sg.getBalance()).signum() > 0).toList();
        if (positive.size() == 1) {
            return positive.get(0);
        }
        if (positive.isEmpty()) {
            throw CodedException.badRequest("refund.amount.exceedsBalance", BigDecimal.ZERO);
        }
        throw CodedException.badRequest("payment.group.required");
    }
}
