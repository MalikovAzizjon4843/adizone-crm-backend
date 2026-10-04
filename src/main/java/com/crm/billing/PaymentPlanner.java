package com.crm.billing;

import com.crm.dto.request.PaymentRequest;
import com.crm.dto.response.BillingLineDto;
import com.crm.entity.BonusPenalty;
import com.crm.entity.CashRegister;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.BalanceTransactionType;
import com.crm.entity.enums.BonusPenaltyKind;
import com.crm.entity.enums.BonusPenaltyStatus;
import com.crm.entity.enums.BonusTargetType;
import com.crm.entity.enums.CashRegisterStatus;
import com.crm.entity.enums.PaymentMethod;
import com.crm.exception.CodedException;
import com.crm.repository.BillingPeriodRepository;
import com.crm.repository.BonusPenaltyRepository;
import com.crm.repository.CashRegisterRepository;
import com.crm.repository.StudentGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * To'lov rejasi — preview va create uchun YAGONA hisob (docs/design/billing-v2.md §5.1, §5.2).
 * Hech narsa yozmaydi. Preview uni qulfsiz, create esa qulf ostida qayta o'qilgan
 * holat bilan chaqiradi — shuning uchun preview'da ko'rsatilgan qatorlar create'da
 * aynan shunday yoziladi (oraliqda boshqa operator o'zgartirmagan bo'lsa).
 */
@Service
@RequiredArgsConstructor
public class PaymentPlanner {

    private final AccrualService accrualService;
    private final BillingStatusService statusService;
    private final BillingPeriodRepository periodRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final BonusPenaltyRepository bonusPenaltyRepository;
    private final CashRegisterRepository cashRegisterRepository;
    private final BillingProperties properties;

    /** Rejaning natijasi. */
    public record PaymentPlan(
        StudentGroup enrollment,
        LocalDate paymentDate,
        BigDecimal gross,
        BigDecimal discount,
        BigDecimal cashAmount,
        String discountReason,
        PaymentMethod cashMethod,
        CashRegister cashRegister,
        boolean convertTrial,
        List<AccrualCalculator.DueCharge> accruals,
        List<BonusPenalty> bonuses,
        List<BillingLineDto> lines,
        BigDecimal balanceBefore,
        BigDecimal debtBefore,
        BillingSnapshot after,
        String planHash,
        List<String> warnings) {

        public BigDecimal bonusTotal() {
            return bonuses.stream().filter(b -> b.getKind() == BonusPenaltyKind.BONUS)
                .map(BonusPenalty::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    // ── 1. Validatsiya ─────────────────────────────────────────────────

    /** Summalar, sana, chegirma huquqi, kassa — yozilmani aniqlashdan oldin. */
    public void validate(PaymentRequest r, LocalDate today) {
        BigDecimal amount = r.getAmount();
        BigDecimal discount = Money.nz(r.getDiscountAmount());
        if (amount == null || amount.signum() <= 0) {
            throw CodedException.badRequest("payment.amount.positive");
        }
        if (!Money.isWhole(amount) || !Money.isWhole(discount)
                || !Money.isWhole(r.getCashPart()) || !Money.isWhole(r.getCardPart())) {
            throw CodedException.badRequest("money.wholeSumRequired");
        }
        if (discount.signum() < 0 || discount.compareTo(amount) > 0) {
            throw CodedException.badRequest("payment.discount.tooLarge");
        }
        if (discount.signum() > 0) {
            // §13 #10 qarori: chegirma — faqat SA, A; sabab majburiy
            if (!BillingAuth.hasAnyRole("SUPER_ADMIN", "ADMIN")) {
                throw CodedException.forbidden("payment.discount.forbidden");
            }
            if (discountReason(r) == null) {
                throw CodedException.badRequest("payment.discount.reasonRequired");
            }
        }
        LocalDate payDate = r.getPaymentDate() != null ? r.getPaymentDate() : today;
        if (payDate.isAfter(today)) {
            throw CodedException.badRequest("payment.date.future");
        }
    }

    static String discountReason(PaymentRequest r) {
        if (r.getDiscountReason() != null && !r.getDiscountReason().isBlank()) {
            return r.getDiscountReason().trim();
        }
        if (r.getNotes() != null && !r.getNotes().isBlank()) {
            return r.getNotes().trim();
        }
        return null;
    }

    // ── 2. Yozilma ──────────────────────────────────────────────────────

    /**
     * {@code groupId} berilgan → shu guruhdagi SG (yo'q — 404). Berilmagan: ochiq yoki
     * qarzli SG lar 1 ta → o'sha; 0 → 400; > 1 → 400 (§5.2 qadam 2, §13 #7). Jim fallback yo'q.
     */
    public StudentGroup resolveEnrollment(Long studentId, Long groupId) {
        List<StudentGroup> all = studentGroupRepository.findByStudentId(studentId);
        if (groupId != null) {
            return all.stream()
                .filter(sg -> sg.getGroup() != null && groupId.equals(sg.getGroup().getId()))
                .max(Comparator
                    .comparing((StudentGroup sg) -> BillingStatusService.isOpen(sg))
                    .thenComparing(sg -> Money.nz(sg.getBalance()).signum() < 0)
                    .thenComparing(StudentGroup::getId))
                .orElseThrow(() -> CodedException.notFound("payment.enrollment.notFound"));
        }
        List<StudentGroup> candidates = all.stream()
            .filter(sg -> BillingStatusService.isOpen(sg) || Money.nz(sg.getBalance()).signum() < 0)
            .toList();
        if (candidates.isEmpty()) {
            throw CodedException.badRequest("payment.enrollment.required");
        }
        if (candidates.size() > 1) {
            throw CodedException.badRequest("payment.group.required");
        }
        return candidates.get(0);
    }

    /** Qo'llanadigan bonus/jarimalar (id o'sish tartibida — qulf tartibi bilan bir xil). */
    public List<BonusPenalty> applicableBonuses(PaymentRequest r, StudentGroup sg, LocalDate payDate) {
        if (Boolean.FALSE.equals(r.getApplyBonuses())) {
            return List.of();
        }
        return bonusPenaltyRepository.findByStudentIdAndStatus(r.getStudentId(), BonusPenaltyStatus.PENDING)
            .stream()
            .filter(b -> b.getTargetType() == BonusTargetType.STUDENT)
            .filter(b -> b.getEffectiveDate() == null || !b.getEffectiveDate().isAfter(payDate))
            .filter(b -> b.getStudentGroupId() == null || b.getStudentGroupId().equals(sg.getId()))
            .sorted(Comparator.comparing(BonusPenalty::getId))
            .toList();
    }

    // ── 3–6. Reja ───────────────────────────────────────────────────────

    /**
     * @param sg      yozilma (create da qulf ostida qayta o'qilgan)
     * @param bonuses qo'llanadigan bonus/jarimalar (create da qulf ostida, faqat PENDING lari)
     */
    public PaymentPlan plan(PaymentRequest r, StudentGroup sg, List<BonusPenalty> bonuses, LocalDate today) {
        LocalDate payDate = r.getPaymentDate() != null ? r.getPaymentDate() : today;
        BigDecimal gross = r.getAmount();
        BigDecimal discount = Money.nz(r.getDiscountAmount());
        BigDecimal cash = gross.subtract(discount);
        List<String> warnings = new ArrayList<>();

        PaymentMethod cashMethod = resolveCashMethod(r);
        CashRegister register = null;
        if (cash.signum() > 0) {
            register = requireRegister(r, cashMethod, cash);
        }

        // 3–4. Kutilayotgan accrual; sinovdagi o'quvchi to'lasa — to'lovli qilinadi (§13 #8), langar = bugun (R5, §14.5)
        boolean convertTrial = Boolean.TRUE.equals(sg.getIsTrial());
        StudentGroup billingView = convertTrial ? asConverted(sg, today) : sg;
        List<BillingCalendar.Span> existing = periodRepository.findByStudentGroupIdOrderByPeriodStartAsc(sg.getId())
            .stream().map(p -> new BillingCalendar.Span(p.getPeriodStart(), p.getPeriodEnd())).toList();
        AccrualCalculator.Result due = AccrualCalculator.dueChargesAfter(
            AccrualCalculator.State.of(billingView), existing, today, properties.getMaxCatchUp());
        if (due.catchUpLimitReached()) {
            warnings.add("billing.catchUp.limit");
        }
        if (due.charges().size() > 1) {
            warnings.add("billing.accrual.multiplePeriods:" + due.charges().size());
        }

        // 5. Ledger qatorlari (yozilish tartibida)
        List<BillingLineDto> lines = new ArrayList<>();
        List<FifoDebt.Line> planned = new ArrayList<>();
        List<LocalDate> plannedStarts = new ArrayList<>();
        for (AccrualCalculator.DueCharge c : due.charges()) {
            plannedStarts.add(c.periodStart());
            if (c.amount().signum() > 0) {
                lines.add(line(BalanceTransactionType.PERIOD_CHARGE, c.amount().negate(), c.periodStart(),
                    AccrualService.periodNote(c), null, true));
                planned.add(new FifoDebt.Line(null, c.amount().negate(), c.periodStart(), null));
            }
        }
        BillingSnapshot beforePayment = statusService.snapshotWith(billingView, planned, plannedStarts, today);
        BigDecimal balanceBefore = statusService.snapshot(sg, today).balance();

        if (cash.signum() > 0) {
            lines.add(line(BalanceTransactionType.PAYMENT, cash, payDate, null, null, null));
            planned.add(new FifoDebt.Line(null, cash, payDate, null));
        }
        if (discount.signum() > 0) {
            lines.add(line(BalanceTransactionType.DISCOUNT, discount, payDate, discountReason(r), null, null));
            planned.add(new FifoDebt.Line(null, discount, payDate, null));
        }
        for (BonusPenalty b : bonuses) {
            boolean bonus = b.getKind() == BonusPenaltyKind.BONUS;
            BigDecimal signed = bonus ? b.getAmount() : b.getAmount().negate();
            LocalDate eff = bonus ? payDate : (b.getEffectiveDate() != null ? b.getEffectiveDate() : payDate);
            lines.add(line(bonus ? BalanceTransactionType.BONUS : BalanceTransactionType.PENALTY,
                signed, eff, b.getReason(), b.getId(), null));
            planned.add(new FifoDebt.Line(null, signed, eff, null));
        }

        // 6. Natija — mavjud ledger + reja qatorlari ustida
        BillingSnapshot after = statusService.snapshotWith(billingView, planned, plannedStarts, today);
        String hash = hash(sg.getId(), convertTrial, lines);
        return new PaymentPlan(sg, payDate, gross, discount, cash, discountReason(r), cashMethod, register,
            convertTrial, due.charges(), bonuses, lines, balanceBefore, beforePayment.debt(), after, hash, warnings);
    }

    private CashRegister requireRegister(PaymentRequest r, PaymentMethod method, BigDecimal cash) {
        if (r.getCashRegisterId() == null) {
            throw CodedException.badRequest("payment.cashRegister.required");
        }
        CashRegister register = cashRegisterRepository.findById(r.getCashRegisterId())
            .orElseThrow(() -> CodedException.notFound("payment.cashRegister.notFound"));
        if (register.isArchived() || register.getStatus() == CashRegisterStatus.ARCHIVED) {
            throw CodedException.badRequest("payment.cashRegister.archived");
        }
        if (method.isOnline() && !register.isAcceptOnlinePayment()) {
            throw CodedException.badRequest("payment.cashRegister.onlineNotAccepted");
        }
        if (method.getCashBucket() == PaymentMethod.CashBucket.SPLIT) {
            BigDecimal parts = Money.nz(r.getCashPart()).add(Money.nz(r.getCardPart()));
            if (r.getCashPart() == null || r.getCardPart() == null || parts.compareTo(cash) != 0) {
                throw CodedException.badRequest("payment.split.mismatch");
            }
        }
        return register;
    }

    static PaymentMethod resolveCashMethod(PaymentRequest r) {
        if (r.getPaymentMethodForCash() != null && !r.getPaymentMethodForCash().isBlank()) {
            PaymentMethod override = PaymentMethod.parseOrNull(r.getPaymentMethodForCash());
            if (override == null) {
                throw CodedException.badRequest("payment.methodForCash.invalid", r.getPaymentMethodForCash());
            }
            return override;
        }
        return r.getPaymentMethod() != null ? r.getPaymentMethod() : PaymentMethod.CASH;
    }

    /** Sinovdan chiqarilgan ko'rinish — entity o'zgarmaydi (preview hech narsa yozmaydi). */
    private static StudentGroup asConverted(StudentGroup sg, LocalDate anchor) {
        return StudentGroup.builder()
            .id(sg.getId())
            .student(sg.getStudent())
            .group(sg.getGroup())
            .joinDate(sg.getJoinDate())
            .leaveDate(sg.getLeaveDate())
            .paymentStartDate(anchor)
            .isTrial(false)
            .isActive(sg.getIsActive())
            .frozenFrom(sg.getFrozenFrom())
            .discountPercentage(sg.getDiscountPercentage())
            .monthlyPriceOverride(sg.getMonthlyPriceOverride())
            .paymentType(sg.getPaymentType())
            .lessonPrice(sg.getLessonPrice())
            .balance(sg.getBalance())
            .build();
    }

    private static BillingLineDto line(BalanceTransactionType type, BigDecimal amount, LocalDate eff,
                                       String note, Long bonusId, Boolean pending) {
        return BillingLineDto.builder()
            .type(type.name())
            .amount(Money.normalize(amount))
            .effectiveDate(eff)
            .note(note)
            .bonusPenaltyId(bonusId)
            .pending(pending)
            .build();
    }

    /** Reja barmoq izi: yozilma + qatorlar (id siz). Preview va create da bir xil bo'lsa — teng. */
    static String hash(Long sgId, boolean convertTrial, List<BillingLineDto> lines) {
        StringBuilder sb = new StringBuilder().append(sgId).append('|').append(convertTrial);
        for (BillingLineDto l : lines) {
            sb.append('|').append(l.getType()).append(':').append(l.getAmount().toPlainString())
                .append(':').append(l.getEffectiveDate()).append(':').append(l.getBonusPenaltyId());
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
