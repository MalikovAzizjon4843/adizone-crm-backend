package com.crm.service;

import com.crm.audit.AuditAction;
import com.crm.audit.Audited;
import com.crm.billing.DebtorService;
import com.crm.billing.PaymentBookingService;
import com.crm.billing.PaymentPlanner;
import com.crm.config.Messages;
import com.crm.dto.request.PaymentPreviewRequest;
import com.crm.dto.request.PaymentRequest;
import com.crm.dto.response.DebtorResponse;
import com.crm.dto.response.DebtorsListResponse;
import com.crm.dto.response.ExpectedPaymentsResponse;
import com.crm.dto.response.PaymentHistoryResponse;
import com.crm.dto.response.PaymentPreviewResponse;
import com.crm.dto.response.PaymentResponse;
import com.crm.dto.response.PaymentSummary;
import com.crm.dto.response.SuspendedStudentResponse;
import com.crm.entity.*;
import com.crm.entity.enums.IncomeCategory;
import com.crm.entity.enums.PaymentMethod;
import com.crm.entity.enums.PaymentStatus;
import com.crm.entity.enums.PaymentType;
import com.crm.entity.enums.BalanceTransactionType;
import com.crm.exception.BadRequestException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PaymentService {


    private final PaymentRepository paymentRepository;
    private final Messages messages;
    private final StudentRepository studentRepository;
    private final GroupRepository groupRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final IncomeRepository incomeRepository;
    private final UserRepository userRepository;
    private final CashRegisterService cashRegisterService;
    private final BonusPenaltyService bonusPenaltyService;
    private final BalanceTransactionService balanceTransactionService;
    private final EntityManager entityManager;
    private final com.crm.billing.DebtorService debtorService;
    private final com.crm.billing.BillingStatusService billingStatusService;
    private final PaymentBookingService paymentBookingService;

    /**
     * Billing v2 to'lovi (docs/design/billing-v2.md §5.3): idempotent, qulf ostida,
     * preview bilan bir xil reja. Takroriy so'rovda ({@code Idempotency-Key}) o'sha
     * to'lov qaytariladi va audit qayta yozilmaydi.
     */
    @Transactional
    @Audited(action = AuditAction.PAYMENT, entity = "Payment",
        summary = "'To''lov qabul qilindi: ' + #result.formattedAmount + ' (' + #result.receiptNumber + ')'",
        entityId = "#result.id",
        label = "#result.studentName")
    public PaymentResponse createPayment(PaymentRequest request, String idempotencyKey) {
        PaymentBookingService.Booked booked = paymentBookingService.create(request, idempotencyKey);
        PaymentResponse response = toResponse(booked.payment());
        response.setLines(booked.lines());
        response.setPlanHash(booked.planHash());
        response.setWarnings(booked.warnings());
        response.setReplay(booked.replay());
        applyAfter(response, booked.after());
        return response;
    }

    /** Eski chaqiruvchilar uchun (kalitsiz). */
    @Transactional
    public PaymentResponse createPayment(PaymentRequest request) {
        return createPayment(request, null);
    }

    /** Dry-run: hech narsa saqlanmaydi, create bilan BITTA reja (§5.1). */
    @Transactional(readOnly = true)
    public PaymentPreviewResponse previewPayment(PaymentRequest request) {
        PaymentPlanner.PaymentPlan plan = paymentBookingService.preview(request);
        StudentGroup sg = plan.enrollment();
        Student student = sg.getStudent();
        return PaymentPreviewResponse.builder()
            .studentGroupId(sg.getId())
            .groupName(sg.getGroup() != null ? sg.getGroup().getGroupName() : null)
            .lines(plan.lines())
            .balanceBefore(plan.balanceBefore())
            .debtBefore(plan.debtBefore())
            .debtAfter(plan.after().debt())
            .statusAfter(plan.after().status().name())
            .nextPaymentDateAfter(plan.after().nextPaymentDate())
            .nextPaymentAmountAfter(plan.after().nextPaymentAmount())
            .convertsTrial(plan.convertTrial())
            .planHash(plan.planHash())
            .warnings(plan.warnings())
            .gross(plan.gross())
            .discount(plan.discount())
            .payable(plan.cashAmount())
            .balanceUsed(BigDecimal.ZERO)
            .cashAmount(plan.cashAmount())
            .studentBalance(nz(student.getBalance()))
            .balanceAfter(plan.after().balance())
            .build();
    }

    /**
     * To'lovni bekor qilish (§6.4) — faqat SUPER_ADMIN (controller), sabab majburiy,
     * {@code paymentDate ≥ bugun − 31 kun} (§13 #23).
     */
    @Transactional
    @Audited(action = AuditAction.PAYMENT_CANCEL, entity = "Payment",
        summary = "'To''lov bekor qilindi: ' + #result.receiptNumber + ', ' + #result.formattedAmount + ' (' + #reason + ')'",
        entityId = "#paymentId",
        label = "#result.studentName")
    public PaymentResponse cancelPayment(Long paymentId, String reason) {
        PaymentBookingService.Cancelled c = paymentBookingService.cancel(paymentId, reason);
        PaymentResponse response = toResponse(c.payment());
        response.setReversalLines(c.reversals());
        response.setWarnings(c.warnings());
        applyAfter(response, c.after());
        return response;
    }

    private static void applyAfter(PaymentResponse response, com.crm.billing.BillingSnapshot after) {
        if (after == null) {
            return;
        }
        response.setBalanceAfter(after.balance());
        response.setDebtAfter(after.debt());
        response.setStatusAfter(after.status().name());
        response.setNextPaymentDate(after.nextPaymentDate());
        response.setNextPaymentAmount(after.nextPaymentAmount());
    }

    /**
     * @deprecated SUSPENDED holati hech qachon qo'yilmagan — ro'yxat doim bo'sh edi.
     * Billing v2 da qarzdorlar {@code GET /api/payments/debtors} da. v2.1 da o'chiriladi.
     */
    @Deprecated
    @Transactional(readOnly = true)
    public List<SuspendedStudentResponse> getArchivedSuspendedStudents() {
        return List.of();
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> getStudentPayments(Long studentId) {
        return paymentRepository.findByStudentIdOrderByPaymentDateDesc(studentId)
            .stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Page<PaymentResponse> getAllPayments(
            int page, int size, Long studentId,
            Long groupId, String status,
            String from, String to) {

        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Specification<Payment> spec = buildPaymentSpec(studentId, groupId, status, from, to);
        return paymentRepository.findAll(spec, pageable).map(this::toResponse);
    }

    /**
     * Filtrga mos BARCHA qatorlar bo'yicha aggregat — sahifadagi qatorlardan emas.
     * {@link #getAllPayments} bilan AYNAN bir xil Specification ishlatiladi, shuning
     * uchun filtr mantiqi ikki joyda ajralib keta olmaydi.
     */
    @Transactional(readOnly = true)
    public PaymentSummary getPaymentsSummary(
            Long studentId, Long groupId, String status, String from, String to) {
        // Billing v2 (§10.2): status filtri berilmasa jami summalar faqat PAID dan —
        // bekor qilingan to'lovlar ro'yxatda ko'rinadi, lekin jamiga kirmaydi.
        String effective = (status == null || status.isBlank()) ? PaymentStatus.PAID.name() : status;
        return summarize(buildPaymentSpec(studentId, groupId, effective, from, to));
    }

    private Specification<Payment> buildPaymentSpec(
            Long studentId, Long groupId, String status, String from, String to) {

        final LocalDate fd = parseDateOrNull(from);
        final LocalDate td = parseDateOrNull(to);
        final PaymentStatus st = parseStatusOrNull(status);
        final Long sId = studentId;
        final Long gId = groupId;

        Specification<Payment> spec = Specification.where(null);
        if (sId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("student").get("id"), sId));
        }
        if (gId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("group").get("id"), gId));
        }
        if (st != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), st));
        }
        if (fd != null) {
            spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("paymentDate"), fd));
        }
        if (td != null) {
            spec = spec.and((root, q, cb) -> cb.lessThanOrEqualTo(root.get("paymentDate"), td));
        }
        return spec;
    }

    /**
     * SUM(gross), SUM(naqd), COUNT — hammasi filtr bo'yicha.
     *
     * <p>DIQQAT: bu yerda COALESCE ISHLATILMAYDI. Hibernate 6 da
     * {@code cb.sum(cb.coalesce(...))} yiqiladi — coalesce ning node tipi
     * {@code SqmBasicValuedSimplePath} bo'lib qoladi, SUM esa
     * {@code ReturnableType} kutadi va ClassCastException chiqadi
     * (butun GET /api/payments 500 bergan edi).
     *
     * <p>Shuning uchun {@code SUM(COALESCE(cash_amount, amount))} ikkiga bo'lingan:
     * <pre>
     * naqd = SUM(cash_amount)              // SQL SUM NULL larni o'zi tashlab ketadi
     *      + SUM(amount) WHERE cash_amount IS NULL   // eski satrlar: amount = naqd edi
     * </pre>
     * Ikkala so'rov ham AYNAN bir xil {@code Specification} obyektidan foydalanadi,
     * ikkinchisiga faqat {@code cash_amount IS NULL} qo'shiladi.
     */
    private PaymentSummary summarize(Specification<Payment> spec) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        // 1) gross, cash_amount to'ldirilgan satrlar bo'yicha naqd, jami soni
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<Payment> root = cq.from(Payment.class);
        Predicate predicate = spec != null ? spec.toPredicate(root, cq, cb) : null;
        if (predicate != null) {
            cq.where(predicate);
        }
        cq.multiselect(
            cb.sum(root.<BigDecimal>get("amount")),
            cb.sum(root.<BigDecimal>get("cashAmount")),
            cb.count(root));
        Object[] row = entityManager.createQuery(cq).getSingleResult();

        // 2) eski satrlar (cash_amount NULL) — o'shanda amount naqd summani bildirgan
        CriteriaQuery<BigDecimal> legacyCq = cb.createQuery(BigDecimal.class);
        Root<Payment> legacyRoot = legacyCq.from(Payment.class);
        Predicate legacyOnly = cb.isNull(legacyRoot.get("cashAmount"));
        Predicate legacySpec = spec != null ? spec.toPredicate(legacyRoot, legacyCq, cb) : null;
        legacyCq.where(legacySpec != null ? cb.and(legacySpec, legacyOnly) : legacyOnly);
        legacyCq.select(cb.sum(legacyRoot.<BigDecimal>get("amount")));
        BigDecimal legacyCash = nz(entityManager.createQuery(legacyCq).getSingleResult());

        return PaymentSummary.builder()
            .totalAmount(nz((BigDecimal) row[0]))
            .totalCashAmount(nz((BigDecimal) row[1]).add(legacyCash))
            .totalCount(row[2] != null ? (Long) row[2] : 0L)
            .build();
    }

    private static LocalDate parseDateOrNull(String value) {
        return (value == null || value.isBlank()) ? null : LocalDate.parse(value);
    }

    private static PaymentStatus parseStatusOrNull(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return PaymentStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Transactional(readOnly = true)
    public List<PaymentHistoryResponse> getPaymentHistory() {
        return paymentRepository.findAllByOrderByPaymentDateDesc().stream()
            .map(this::toHistoryResponse)
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getPaymentStats() {
        LocalDate today = LocalDate.now();
        YearMonth ym = YearMonth.from(today);
        LocalDate monthStart = ym.atDay(1);
        LocalDate monthEnd = ym.atEndOfMonth();
        YearMonth prev = ym.minusMonths(1);
        LocalDate prevStart = prev.atDay(1);
        LocalDate prevEnd = prev.atEndOfMonth();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalCollected", nz(paymentRepository.sumCashAmountByStatus(PaymentStatus.PAID)));
        // v2: kutilayotgan = PENDING (grace ichidagi) SG lar qarzi (§10.2)
        LocalDate billingToday = billingStatusService.today();
        BigDecimal pendingDebt = studentGroupRepository.findWithDebt().stream()
            .filter(sg -> billingStatusService.isPending(sg, billingToday))
            .map(sg -> sg.getBalance().negate())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        stats.put("totalPending", pendingDebt);
        stats.put("thisMonth", nz(paymentRepository.sumCashPaidBetween(monthStart, monthEnd)));
        stats.put("lastMonth", nz(paymentRepository.sumCashPaidBetween(prevStart, prevEnd)));
        return stats;
    }

    /**
     * Eski yozuvlarda payable/cashAmount ustunlari bo'sh: o'shanda amount kassaga
     * tushgan summani bildirgan, ya'ni payable = amount + balanceUsed.
     */
    private static BigDecimal resolvePayable(Payment p) {
        if (p.getPayableAmount() != null) {
            return p.getPayableAmount();
        }
        return nz(p.getAmount()).add(nz(p.getBalanceUsed()));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    /** Yagona ta'rif (§4.5): ro'yxatda faqat OVERDUE o'quvchilar. */
    @Transactional(readOnly = true)
    public DebtorsListResponse getDebtors(DebtorService.Filter filter) {
        return debtorService.debtors(filter, billingStatusService.today());
    }

    @Transactional(readOnly = true)
    public ExpectedPaymentsResponse getExpectedPayments(LocalDate from, LocalDate to) {
        return debtorService.expected(from, to, billingStatusService.today());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getDebtorsSummary(DebtorService.Scope scope) {
        return debtorService.debtorSummary(
            new DebtorService.Filter(scope, null, null, null, null), billingStatusService.today()).toMap();
    }

    private PaymentResponse toResponse(Payment p) {
        return PaymentResponse.builder()
            .id(p.getId())
            .uuid(p.getUuid())
            .studentId(p.getStudent().getId())
            .studentName(p.getStudent().getFirstName() + " " + p.getStudent().getLastName())
            .groupId(p.getGroup() != null ? p.getGroup().getId() : null)
            .groupName(p.getGroup() != null ? p.getGroup().getGroupName() : null)
            .amount(p.getAmount())
            .discountAmount(p.getDiscountAmount() != null ? p.getDiscountAmount() : BigDecimal.ZERO)
            .bonusDiscount(p.getBonusDiscount() != null ? p.getBonusDiscount() : BigDecimal.ZERO)
            .balanceUsed(p.getBalanceUsed() != null ? p.getBalanceUsed() : BigDecimal.ZERO)
            .payable(resolvePayable(p))
            .cashAmount(p.getCashAmount() != null ? p.getCashAmount() : nz(p.getAmount()))
            .receiptNumber(p.getReceiptNumber())
            .formattedAmount(formatUzs(p.getAmount()))
            .paymentDate(p.getPaymentDate())
            .paymentMethod(p.getPaymentMethod())
            .status(p.getStatus())
            .periodFrom(p.getPeriodFrom())
            .periodTo(p.getPeriodTo())
            .description(p.getDescription())
            .createdAt(p.getCreatedAt())
            .cashRegisterId(p.getCashRegister() != null ? p.getCashRegister().getId() : null)
            .cashRegisterName(p.getCashRegister() != null ? p.getCashRegister().getName() : null)
            .studentGroupId(p.getStudentGroup() != null ? p.getStudentGroup().getId() : null)
            .cancelledAt(p.getCancelledAt())
            .cancelledByName(p.getCancelledBy() != null
                ? (p.getCancelledBy().getFirstName() + " " + p.getCancelledBy().getLastName()).trim() : null)
            .cancelReason(p.getCancelReason())
            .build();
    }

    private PaymentHistoryResponse toHistoryResponse(Payment p) {
        return PaymentHistoryResponse.builder()
            .receiptNumber(p.getReceiptNumber())
            .studentName(p.getStudent().getFirstName() + " " + p.getStudent().getLastName())
            .groupName(p.getGroup() != null ? p.getGroup().getGroupName() : null)
            .amount(p.getAmount())
            .paymentDate(p.getPaymentDate())
            .paymentMethod(p.getPaymentMethod())
            .periodFrom(p.getPeriodFrom())
            .periodTo(p.getPeriodTo())
            .status(p.getStatus())
            .description(p.getDescription())
            .build();
    }

    private static String formatUzs(BigDecimal amount) {
        if (amount == null) {
            return "0 so'm";
        }
        long v = amount.setScale(0, RoundingMode.HALF_UP).longValue();
        String s = String.format(Locale.US, "%,d", v).replace(',', ' ');
        return s + " so'm";
    }

    /**
     * @deprecated v2.1 da o'chiriladi. Eski "kun/30 × narx − Σgross" formulasi o'rniga
     * yagona snapshot (§4.4): {@code {debt, balance, debtSince, status}}.
     */
    @Deprecated
    @Transactional(readOnly = true)
    public Map<String, Object> calculateStudentDebt(Long studentId, Long groupId) {
        Map<String, Object> result = new LinkedHashMap<>();
        StudentGroup sg = studentGroupRepository.findByStudentId(studentId).stream()
            .filter(g -> g.getGroup() != null && g.getGroup().getId().equals(groupId))
            .max(Comparator.comparing(StudentGroup::getId))
            .orElse(null);
        result.put("studentId", studentId);
        result.put("groupId", groupId);
        if (sg == null) {
            result.put("debt", BigDecimal.ZERO);
            result.put("message", "Guruh topilmadi");
            return result;
        }
        com.crm.billing.BillingSnapshot s = billingStatusService.snapshot(sg, billingStatusService.today());
        result.put("studentGroupId", sg.getId());
        result.put("debt", s.debt());
        result.put("balance", s.balance());
        result.put("debtSince", s.debtSince());
        result.put("status", s.status());
        result.put("nextPaymentDate", s.nextPaymentDate());
        result.put("nextPaymentAmount", s.nextPaymentAmount());
        result.put("message", s.debt().signum() > 0 ? "Qarz: " + formatUzs(s.debt()) : "Qarzdorlik yo'q");
        return result;
    }

}
