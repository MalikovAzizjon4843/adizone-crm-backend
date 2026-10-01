package com.crm.billing;

import com.crm.audit.AuditContext;
import com.crm.dto.request.PaymentRequest;
import com.crm.dto.response.BillingLineDto;
import com.crm.entity.BalanceTransaction;
import com.crm.entity.BonusPenalty;
import com.crm.entity.CashTransaction;
import com.crm.entity.Income;
import com.crm.entity.Payment;
import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.entity.User;
import com.crm.entity.enums.BalanceTransactionType;
import com.crm.entity.enums.BonusPenaltyKind;
import com.crm.entity.enums.BonusPenaltyStatus;
import com.crm.entity.enums.CashTransactionType;
import com.crm.entity.enums.IncomeCategory;
import com.crm.entity.enums.PaymentStatus;
import com.crm.exception.CodedException;
import com.crm.exception.ConflictException;
import com.crm.repository.BalanceTransactionRepository;
import com.crm.repository.BonusPenaltyRepository;
import com.crm.repository.CashTransactionRepository;
import com.crm.repository.IncomeRepository;
import com.crm.repository.PaymentRepository;
import com.crm.repository.StudentRepository;
import com.crm.repository.UserRepository;
import com.crm.service.CashRegisterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * To'lovni yozish va bekor qilish — docs/design/billing-v2.md §5.3, §6.4.
 *
 * <p>Create: idempotentlik → qulf (student → SG → bonuslar → kassa) → qulf ostida
 * qayta o'qilgan holat bilan AYNAN {@link PaymentPlanner#plan} → reja qatorlarini
 * yozish. Bekor qilish: qulf → har bir bog'langan ledger yozuviga REVERSAL, bonuslar
 * PENDING ga, kassa REVERSAL, to'lov CANCELLED.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentBookingService {

    private final PaymentPlanner planner;
    private final BillingLocks locks;
    private final AccrualService accrualService;
    private final LedgerService ledger;
    private final BillingSnapshotService snapshotService;
    private final BillingStatusService statusService;
    private final ReceiptNumberService receipts;
    private final BillingGate gate;
    private final BillingProperties properties;
    private final PaymentRepository paymentRepository;
    private final StudentRepository studentRepository;
    private final BonusPenaltyRepository bonusPenaltyRepository;
    private final IncomeRepository incomeRepository;
    private final UserRepository userRepository;
    private final BalanceTransactionRepository transactionRepository;
    private final CashTransactionRepository cashTransactionRepository;
    private final CashRegisterService cashRegisterService;
    private final java.time.Clock billingClock;

    /** Yozilgan (yoki takroriy so'rovda topilgan) to'lov. */
    public record Booked(
        Payment payment,
        List<BillingLineDto> lines,
        BillingSnapshot after,
        List<String> warnings,
        String planHash,
        boolean replay) {
    }

    // ── Preview ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PaymentPlanner.PaymentPlan preview(PaymentRequest r) {
        LocalDate today = statusService.today();
        requireStudent(r.getStudentId());
        planner.validate(r, today);
        StudentGroup sg = planner.resolveEnrollment(r.getStudentId(), r.getGroupId());
        LocalDate payDate = r.getPaymentDate() != null ? r.getPaymentDate() : today;
        return planner.plan(r, sg, planner.applicableBonuses(r, sg, payDate), today);
    }

    // ── Create ─────────────────────────────────────────────────────────

    @Transactional
    public Booked create(PaymentRequest r, String idempotencyKey) {
        gate.requireWritable();
        String key = idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey.trim() : null;
        if (key != null && key.length() > 64) {
            throw CodedException.badRequest("payment.idempotency.keyTooLong");
        }
        String fingerprint = fingerprint(r);
        Booked replay = replayIfExists(key, fingerprint);
        if (replay != null) {
            return replay;
        }

        LocalDate today = statusService.today();
        Student student = requireStudent(r.getStudentId());
        planner.validate(r, today);
        LocalDate payDate = r.getPaymentDate() != null ? r.getPaymentDate() : today;

        // Qulfsiz aniqlash → qulf → qulf ostida qayta o'qish
        StudentGroup candidate = planner.resolveEnrollment(r.getStudentId(), r.getGroupId());
        List<Long> bonusIds = planner.applicableBonuses(r, candidate, payDate).stream()
            .map(BonusPenalty::getId).toList();
        boolean hasCash = r.getAmount().subtract(Money.nz(r.getDiscountAmount())).signum() > 0;
        BillingLocks.Locked locked = locks.acquire(BillingLocks.Plan.of()
            .student(student.getId())
            .enrollment(candidate.getId())
            .bonuses(bonusIds)
            .cashRegister(hasCash ? r.getCashRegisterId() : null));

        // Parallel bir xil kalit: birinchisi commit bo'lgach shu yerda ko'rinadi
        replay = replayIfExists(key, fingerprint);
        if (replay != null) {
            return replay;
        }

        StudentGroup sg = locked.enrollment(candidate.getId());
        List<BonusPenalty> bonuses = locked.bonuses().stream()
            .filter(b -> b.getStatus() == BonusPenaltyStatus.PENDING)
            .toList();
        PaymentPlanner.PaymentPlan plan = planner.plan(r, sg, bonuses, today);
        if (r.getExpectedPlanHash() != null && !r.getExpectedPlanHash().equals(plan.planHash())) {
            throw new ConflictException("payment.plan.changed");
        }

        List<BillingLineDto> written = new ArrayList<>();

        // Sinov → to'lovli (langar = to'lov sanasi), keyin rejadagi accrual
        if (plan.convertTrial()) {
            sg.setIsTrial(false);
            sg.setPaymentStartDate(plan.paymentDate());
        }
        AccrualService.AccrualResult accrued = accrualService.accrueLocked(sg, today);
        accrued.created().forEach(p -> {
            if (p.getChargeTxId() != null) {
                written.add(BillingLineDto.builder()
                    .type(BalanceTransactionType.PERIOD_CHARGE.name())
                    .amount(Money.normalize(p.getAmount().negate()))
                    .effectiveDate(p.getPeriodStart())
                    .note(p.getPeriodStart() + "–" + p.getPeriodEnd())
                    .ledgerTxId(p.getChargeTxId())
                    .build());
            }
        });

        Payment payment = paymentRepository.save(Payment.builder()
            .student(student)
            .group(sg.getGroup())
            .studentGroup(sg)
            .amount(plan.gross())
            .discountAmount(plan.discount())
            .bonusDiscount(plan.bonusTotal())
            .payableAmount(plan.cashAmount())
            .cashAmount(plan.cashAmount())
            .balanceUsed(BigDecimal.ZERO)
            .receiptNumber(receipts.next())
            .paymentDate(plan.paymentDate())
            .paymentMethod(r.getPaymentMethod() != null ? r.getPaymentMethod() : com.crm.entity.enums.PaymentMethod.CASH)
            .status(PaymentStatus.PAID)
            .description(r.getDescription())
            .notes(r.getNotes())
            .receivedBy(currentUser())
            .idempotencyKey(key)
            .idempotencyHash(fingerprint)
            .build());

        if (plan.cashAmount().signum() > 0) {
            written.add(withId(lineOf(plan, BalanceTransactionType.PAYMENT), post(sg,
                BalanceTransactionType.PAYMENT, plan.cashAmount(), plan.paymentDate(), payment.getId(),
                "To'lov #" + payment.getReceiptNumber())));
        }
        if (plan.discount().signum() > 0) {
            written.add(withId(lineOf(plan, BalanceTransactionType.DISCOUNT), post(sg,
                BalanceTransactionType.DISCOUNT, plan.discount(), plan.paymentDate(), payment.getId(),
                "Chegirma #" + payment.getReceiptNumber() + ": " + plan.discountReason())));
        }
        for (BonusPenalty b : plan.bonuses()) {
            boolean isBonus = b.getKind() == BonusPenaltyKind.BONUS;
            LocalDate eff = isBonus ? plan.paymentDate()
                : (b.getEffectiveDate() != null ? b.getEffectiveDate() : plan.paymentDate());
            BalanceTransaction tx = post(sg, isBonus ? BalanceTransactionType.BONUS : BalanceTransactionType.PENALTY,
                isBonus ? b.getAmount() : b.getAmount().negate(), eff, b.getId(),
                (isBonus ? "Bonus" : "Jarima") + " #" + payment.getReceiptNumber()
                    + (b.getReason() != null ? ": " + b.getReason() : ""));
            b.setStatus(BonusPenaltyStatus.APPLIED);
            b.setAppliedToPaymentId(payment.getId());
            b.setLedgerTxId(tx.getId());
            b.setStudentGroupId(sg.getId());
            bonusPenaltyRepository.save(b);
            written.add(BillingLineDto.builder().type(tx.getType().name()).amount(Money.normalize(tx.getAmount()))
                .effectiveDate(eff).note(b.getReason()).bonusPenaltyId(b.getId()).ledgerTxId(tx.getId()).build());
        }

        if (plan.cashAmount().signum() > 0) {
            CashTransaction cashTx = cashRegisterService.recordIncome(
                plan.cashRegister().getId(), plan.cashAmount(), plan.cashMethod(), student,
                "O'quvchi to'lovi", "To'lov #" + payment.getReceiptNumber(), plan.paymentDate(),
                r.getCashPart(), r.getCardPart(), payment.getId());
            payment.setCashRegister(cashTx.getCashRegister());
            payment.setCashTransactionId(cashTx.getId());
            incomeRepository.save(Income.builder()
                .category(IncomeCategory.STUDENT_PAYMENT)
                .amount(plan.cashAmount())
                .payment(payment)
                .description("Student payment: " + student.getFirstName() + " " + student.getLastName())
                .incomeDate(plan.paymentDate())
                .receivedBy(payment.getReceivedBy())
                .build());
        }
        paymentRepository.save(payment);

        BillingSnapshot after = snapshotService.refresh(sg);
        return new Booked(payment, written, after, plan.warnings(), plan.planHash(), false);
    }

    private Booked replayIfExists(String key, String fingerprint) {
        if (key == null) {
            return null;
        }
        return paymentRepository.findByIdempotencyKey(key).map(existing -> {
            if (!Objects.equals(existing.getIdempotencyHash(), fingerprint)) {
                throw new ConflictException("payment.idempotency.mismatch");
            }
            AuditContext.skip();
            BillingSnapshot after = existing.getStudentGroup() != null
                ? statusService.snapshot(existing.getStudentGroup(), statusService.today()) : null;
            return new Booked(existing, linesOfPayment(existing), after, List.of(), null, true);
        }).orElse(null);
    }

    /** Takroriy so'rov va bekor qilish uchun: shu to'lovga bog'langan ledger qatorlari. */
    public List<BalanceTransaction> ledgerOf(Payment p) {
        List<BalanceTransaction> txs = new ArrayList<>();
        if (p.getStudentGroup() != null) {
            txs.addAll(transactionRepository.findByStudentGroup_IdAndReferenceIdAndTypeIn(
                p.getStudentGroup().getId(), p.getId(),
                List.of(BalanceTransactionType.PAYMENT, BalanceTransactionType.DISCOUNT)));
        }
        for (BonusPenalty b : bonusPenaltyRepository.findByAppliedToPaymentId(p.getId())) {
            if (b.getLedgerTxId() != null) {
                transactionRepository.findById(b.getLedgerTxId()).ifPresent(txs::add);
            }
        }
        return txs;
    }

    private List<BillingLineDto> linesOfPayment(Payment p) {
        return ledgerOf(p).stream().map(PaymentBookingService::toLine).toList();
    }

    // ── Bekor qilish (§6.4) ────────────────────────────────────────────

    public record Cancelled(Payment payment, List<BillingLineDto> reversals, BillingSnapshot after,
                            List<String> warnings) {
    }

    @Transactional
    public Cancelled cancel(Long paymentId, String reason) {
        gate.requireWritable();
        String why = reason != null ? reason.trim() : "";
        if (why.length() < 3 || why.length() > 500) {
            throw CodedException.badRequest("payment.cancel.reasonRequired");
        }
        Payment found = paymentRepository.findById(paymentId)
            .orElseThrow(() -> CodedException.notFound("payment.notFound", paymentId));
        List<Long> bonusIds = bonusPenaltyRepository.findByAppliedToPaymentId(paymentId).stream()
            .map(BonusPenalty::getId).toList();
        BillingLocks.Locked locked = locks.acquire(BillingLocks.Plan.of()
            .student(found.getStudent().getId())
            .enrollment(found.getStudentGroup() != null ? found.getStudentGroup().getId() : null)
            .payment(paymentId)
            .bonuses(bonusIds)
            .cashRegister(found.getCashRegister() != null ? found.getCashRegister().getId() : null));

        Payment payment = locked.payment();
        if (payment.getStatus() != PaymentStatus.PAID) {
            throw new ConflictException("payment.alreadyCancelled");
        }
        LocalDate today = statusService.today();
        if (payment.getPaymentDate().isBefore(today.minusDays(properties.getCancelMaxAgeDays()))) {
            // §13 #23 qarori: eskisi SA MANUAL_ADJUST orqali tuzatiladi
            throw CodedException.badRequest("payment.cancel.tooOld", properties.getCancelMaxAgeDays());
        }

        List<BillingLineDto> reversals = new ArrayList<>();
        String note = "Bekor qilindi: " + payment.getReceiptNumber() + " — " + why;
        for (BalanceTransaction original : ledgerOf(payment)) {
            boolean reversed = transactionRepository.findByRelatedTxId(original.getId()).stream()
                .anyMatch(t -> t.getType() == BalanceTransactionType.REVERSAL);
            if (!reversed) {
                reversals.add(toLine(ledger.reverse(original, note)));
            }
        }
        for (BonusPenalty b : locked.bonuses()) {
            b.setStatus(BonusPenaltyStatus.PENDING);
            b.setAppliedToPaymentId(null);
            b.setLedgerTxId(null);
            bonusPenaltyRepository.save(b);
        }

        List<String> warnings = new ArrayList<>();
        CashTransaction income = findIncome(payment);
        if (income != null) {
            CashRegisterService.ReversalResult r = cashRegisterService.recordReversal(income, note);
            if (r.negativeBalance()) {
                warnings.add("cash.negativeBalance");
            }
        } else if (Money.nz(payment.getCashAmount()).signum() > 0) {
            warnings.add("cash.incomeNotFound");
        }

        payment.setStatus(PaymentStatus.CANCELLED);
        payment.setCancelledAt(LocalDateTime.now(billingClock));
        payment.setCancelledBy(currentUser());
        payment.setCancelReason(why);
        paymentRepository.save(payment);

        BillingSnapshot after = payment.getStudentGroup() != null
            ? snapshotService.refresh(payment.getStudentGroup()) : null;
        return new Cancelled(payment, reversals, after, warnings);
    }

    /** I5: kirim to'lovga bog'langan; eski to'lovlarda — chek raqami izohi bo'yicha. */
    private CashTransaction findIncome(Payment p) {
        if (p.getCashTransactionId() != null) {
            return cashTransactionRepository.findById(p.getCashTransactionId()).orElse(null);
        }
        if (p.getCashRegister() == null || p.getReceiptNumber() == null) {
            return null;
        }
        return cashTransactionRepository.findFirstByCashRegister_IdAndTypeAndNote(
            p.getCashRegister().getId(), CashTransactionType.INCOME, "To'lov #" + p.getReceiptNumber())
            .orElse(null);
    }

    // ── yordamchilar ────────────────────────────────────────────────────

    private BalanceTransaction post(StudentGroup sg, BalanceTransactionType type, BigDecimal amount,
                                    LocalDate eff, Long referenceId, String note) {
        return ledger.post(LedgerService.Entry.builder()
            .enrollment(sg).type(type).amount(amount).effectiveDate(eff)
            .referenceId(referenceId).note(note).build());
    }

    private static BillingLineDto lineOf(PaymentPlanner.PaymentPlan plan, BalanceTransactionType type) {
        return plan.lines().stream().filter(l -> l.getType().equals(type.name())).findFirst()
            .orElseThrow();
    }

    private static BillingLineDto withId(BillingLineDto planned, BalanceTransaction tx) {
        return BillingLineDto.builder()
            .type(planned.getType()).amount(planned.getAmount()).effectiveDate(planned.getEffectiveDate())
            .note(planned.getNote()).ledgerTxId(tx.getId()).build();
    }

    static BillingLineDto toLine(BalanceTransaction t) {
        return BillingLineDto.builder()
            .type(t.getType().name())
            .amount(Money.normalize(t.getAmount()))
            .effectiveDate(t.getEffectiveDate())
            .note(t.getNote())
            .ledgerTxId(t.getId())
            .build();
    }

    private Student requireStudent(Long id) {
        if (id == null) {
            throw CodedException.badRequest("payment.student.required");
        }
        return studentRepository.findById(id)
            .orElseThrow(() -> CodedException.notFound("error.student.notFound", id));
    }

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            return null;
        }
        return userRepository.findByUsername(auth.getName()).orElse(null);
    }

    /** So'rov tanasining barmoq izi (expectedPlanHash dan tashqari). */
    static String fingerprint(PaymentRequest r) {
        String raw = String.join("|",
            String.valueOf(r.getStudentId()), String.valueOf(r.getGroupId()),
            plain(r.getAmount()), plain(r.getDiscountAmount()),
            String.valueOf(r.getPaymentDate()), String.valueOf(r.getPaymentMethod()),
            String.valueOf(r.getPaymentMethodForCash()), plain(r.getCashPart()), plain(r.getCardPart()),
            String.valueOf(r.getCashRegisterId()), String.valueOf(r.getApplyBonuses()),
            String.valueOf(r.getDescription()), String.valueOf(r.getNotes()), String.valueOf(r.getDiscountReason()));
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String plain(BigDecimal v) {
        return v == null ? "null" : v.stripTrailingZeros().toPlainString();
    }
}
