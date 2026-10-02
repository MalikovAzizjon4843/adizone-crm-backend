package com.crm.service;

import com.crm.audit.AuditAction;
import com.crm.audit.Audited;
import com.crm.billing.BillingAuth;
import com.crm.billing.BillingLocks;
import com.crm.dto.request.PayrollPayDto;
import com.crm.dto.response.PageResponse;
import com.crm.dto.response.PayrollCalculationDetails;
import com.crm.dto.response.PayrollGenerateResult;
import com.crm.dto.response.PayrollResponse;
import com.crm.dto.response.SalaryCalculationDto;
import com.crm.entity.BonusPenalty;
import com.crm.entity.CashTransaction;
import com.crm.entity.Payroll;
import com.crm.entity.Teacher;
import com.crm.entity.User;
import com.crm.entity.enums.BonusPenaltyStatus;
import com.crm.entity.enums.PaymentMethod;
import com.crm.entity.enums.PayrollStatus;
import com.crm.exception.CodedException;
import com.crm.exception.ConflictException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.BonusPenaltyRepository;
import com.crm.repository.CashTransactionRepository;
import com.crm.repository.PayrollRepository;
import com.crm.repository.TeacherRepository;
import com.crm.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Oylik (payroll) hayot sikli — docs/design/payroll-v2.md.
 *
 * <pre>
 * DRAFT ──approve──► APPROVED ──pay──► PAID
 *   │                   │               │
 * DELETE            cancel (SA)     cancel (SA, kassaga REVERSAL)
 * </pre>
 *
 * <ul>
 *   <li>Holat faqat shu klassdagi amallar orqali o'zgaradi; {@code PUT}/ixtiyoriy status yo'q.</li>
 *   <li>generate/recalculate faqat DRAFT ga tegadi — APPROVED/PAID summasi hech qachon qayta yozilmaydi.</li>
 *   <li>Bonuslar DRAFT da faqat ko'rinadi (PENDING), APPROVE da qulf ostida APPLIED bo'ladi,
 *       bekor qilishda PENDING ga qaytadi.</li>
 *   <li>Qulf: {@link BillingLocks} — Payroll → BonusPenalty → CashRegister.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PayrollService {

    private static final int IDEMPOTENCY_KEY_MAX = 64;

    private final PayrollRepository payrollRepository;
    private final TeacherRepository teacherRepository;
    private final UserRepository userRepository;
    private final BonusPenaltyRepository bonusPenaltyRepository;
    private final CashTransactionRepository cashTransactionRepository;
    private final CashRegisterService cashRegisterService;
    private final SalaryCalculationService calculator;
    private final BillingLocks locks;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;
    private final Clock clock;

    // ── o'qish ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<PayrollResponse> getAllPayroll(int page, int size, String status,
                                                       Integer month, Integer year, Long userId) {
        Specification<Payroll> spec = Specification.where(null);
        if (status != null && !status.isBlank()) {
            PayrollStatus st = PayrollStatus.parseOrNull(status);
            if (st == null) {
                throw CodedException.badRequest("payroll.status.invalid", status);
            }
            spec = spec.and((r, q, cb) -> cb.equal(r.get("status"), st));
        }
        if (month != null) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("month"), month));
        }
        if (year != null) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("year"), year));
        }
        if (userId != null) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("user").get("id"), userId));
        }
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("year"), Sort.Order.desc("month"),
            Sort.Order.desc("id")));
        Page<Payroll> p = payrollRepository.findAll(spec, pageable);
        return PageResponse.<PayrollResponse>builder()
            .content(p.getContent().stream().map(this::toResponse).toList())
            .pageNumber(page).pageSize(size)
            .totalElements(p.getTotalElements()).totalPages(p.getTotalPages()).last(p.isLast())
            .build();
    }

    @Transactional(readOnly = true)
    public PayrollResponse getPayrollById(Long id) {
        return toResponse(findById(id));
    }

    @Transactional(readOnly = true)
    public List<PayrollResponse> getPayrollByTeacher(Long teacherId) {
        return payrollRepository.findByTeacherIdOrderByYearDescMonthDesc(teacherId).stream()
            .map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<SalaryCalculationDto> previewCalculate(int month, int year) {
        return calculator.calculateAll(month, year);
    }

    @Transactional(readOnly = true)
    public SalaryCalculationDto previewCalculateUser(Long userId, int month, int year) {
        return calculator.calculateForUser(userId, month, year);
    }

    // ── DRAFT: generate / recalculate / delete ──────────────────────────

    /**
     * Yetishmayotgan xodimlarga DRAFT; mavjud DRAFT faqat {@code recalculate=true} bilan
     * qayta hisoblanadi; APPROVED/PAID ga tegilmaydi (payroll-v2 §1.2).
     *
     * <p>Har xodim ALOHIDA tranzaksiyada: bittasidagi baza xatosi (masalan eski sxemadagi
     * cheklov, payroll-v2 §12) butun so'rovni 500 qilmaydi — o'sha xodim {@code skipped}
     * ga {@code reason = ERROR} va sabab bilan tushadi, qolganlari saqlanadi.
     */
    @Audited(action = AuditAction.CREATE, entity = "Payroll",
        summary = "'Oylik hisoblandi: ' + #month + '/' + #year + ' (yangi: ' + #result.created()"
            + " + ', qayta: ' + #result.recalculated() + ')'")
    public PayrollGenerateResult generatePayroll(int month, int year, boolean recalculate) {
        List<SalaryCalculationDto> calcs = calculator.calculateAll(month, year);
        int created = 0;
        int recalculated = 0;
        List<PayrollGenerateResult.Skipped> skipped = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        Long actorId = currentUser() != null ? currentUser().getId() : null;
        TransactionTemplate perStaff = new TransactionTemplate(transactionManager);
        perStaff.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        for (SalaryCalculationDto calc : calcs) {
            if (!Boolean.TRUE.equals(calc.getCalculable())) {
                skipped.add(new PayrollGenerateResult.Skipped(calc.getUserId(), calc.getFullName(),
                    "NOT_CALCULABLE", calc.getMessageCode(), calc.getMessage(), null));
                continue;
            }
            GenerateOutcome outcome;
            try {
                outcome = perStaff.execute(s -> generateOne(calc, month, year, recalculate, actorId));
            } catch (RuntimeException e) {
                log.error("Oylik generate xatosi: userId={}, {}/{}", calc.getUserId(), month, year, e);
                skipped.add(new PayrollGenerateResult.Skipped(calc.getUserId(), calc.getFullName(),
                    "ERROR", e instanceof DataIntegrityViolationException ? "DB_CONSTRAINT" : "UNEXPECTED",
                    rootMessage(e), null));
                continue;
            }
            switch (outcome.kind()) {
                case CREATED -> {
                    created++;
                    total = total.add(outcome.net());
                }
                case RECALCULATED -> {
                    recalculated++;
                    total = total.add(outcome.net());
                }
                default -> skipped.add(outcome.skipped());
            }
        }
        return new PayrollGenerateResult(created, recalculated, skipped, total);
    }

    private enum GenerateKind { CREATED, RECALCULATED, SKIPPED }

    private record GenerateOutcome(GenerateKind kind, BigDecimal net, PayrollGenerateResult.Skipped skipped) {
    }

    /** Bitta xodim — chaqiruvchi tranzaksiyasida (generate da REQUIRES_NEW). */
    private GenerateOutcome generateOne(SalaryCalculationDto calc, int month, int year, boolean recalculate,
                                        Long actorId) {
        User user = userRepository.findById(calc.getUserId()).orElseThrow();
        Payroll existing = payrollRepository.findActive(user.getId(), month, year).orElse(null);
        if (existing == null) {
            Payroll p = new Payroll();
            p.setUser(user);
            p.setMonth(month);
            p.setYear(year);
            p.setStatus(PayrollStatus.DRAFT);
            p.setCreatedBy(actorId != null ? userRepository.findById(actorId).orElse(null) : null);
            applyCalculation(p, calc);
            payrollRepository.saveAndFlush(p);
            return new GenerateOutcome(GenerateKind.CREATED, nz(p.getNetSalary()), null);
        }
        if (existing.getStatus() == PayrollStatus.DRAFT && recalculate) {
            applyCalculation(existing, calc);
            payrollRepository.saveAndFlush(existing);
            return new GenerateOutcome(GenerateKind.RECALCULATED, nz(existing.getNetSalary()), null);
        }
        String reason = existing.getStatus() == PayrollStatus.DRAFT ? "DRAFT_EXISTS" : existing.getStatus().name();
        return new GenerateOutcome(GenerateKind.SKIPPED, BigDecimal.ZERO,
            new PayrollGenerateResult.Skipped(user.getId(), calc.getFullName(), reason, null, null, existing.getId()));
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() != null ? cur.getMessage() : cur.getClass().getSimpleName();
    }

    @Transactional
    @Audited(action = AuditAction.UPDATE, entity = "Payroll",
        summary = "'Oylik qayta hisoblandi: ' + #result.userName + ' ' + #result.month + '/' + #result.year",
        entityId = "#id", label = "#result.userName")
    public PayrollResponse recalculate(Long id) {
        Payroll p = locks.acquire(BillingLocks.Plan.of().payroll(id)).payroll();
        requireDraft(p);
        SalaryCalculationDto calc = calculator.calculate(p.getUser(), p.getMonth(), p.getYear());
        if (!Boolean.TRUE.equals(calc.getCalculable())) {
            throw CodedException.badRequest("payroll.notCalculable", calc.getMessage());
        }
        applyCalculation(p, calc);
        return toResponse(payrollRepository.save(p));
    }

    /** Faqat DRAFT — bonuslar PENDING, kassa yozuvi yo'q: qaytaradigan narsa yo'q. */
    @Transactional
    @Audited(action = AuditAction.DELETE, entity = "Payroll",
        summary = "'Oylik qoralamasi o''chirildi: #' + #id", entityId = "#id")
    public void deletePayroll(Long id) {
        Payroll p = locks.acquire(BillingLocks.Plan.of().payroll(id)).payroll();
        requireDraft(p);
        payrollRepository.delete(p);
    }

    // ── APPROVE ─────────────────────────────────────────────────────────

    /**
     * DRAFT → APPROVED. Gross — DRAFT dagi snapshot; bonuslar (TEACHER yoki STAFF) qulf ostida qayta
     * o'qiladi va APPLIED bo'ladi (payroll-v2 §4, §11 #5). {@code net < 0} → 400.
     *
     * <p>§11 #4: {@code expectedNetSalary} berilsa va qulf ostidagi yakuniy {@code net} dan farq
     * qilsa — 409 {@code payroll.netChanged} (javob {@code data.netSalary} da yangi summa), hech narsa
     * yozilmaydi. Berilmasa — avvalgidek.
     */
    @Transactional
    @Audited(action = AuditAction.STATUS_CHANGE, entity = "Payroll",
        summary = "'Oylik tasdiqlandi: ' + #result.userName + ' ' + #result.month + '/' + #result.year"
            + " + ' = ' + #result.netSalary",
        entityId = "#id", label = "#result.userName")
    public PayrollResponse approve(Long id, BigDecimal expectedNetSalary) {
        Payroll candidate = findById(id);
        requireDraft(candidate);
        if (!Integer.valueOf(PayrollCalculationDetails.VERSION).equals(candidate.getCalcVersion())) {
            // v1 dan qolgan qoralama — v2 snapshot yo'q
            throw new ConflictException("payroll.recalculateRequired");
        }
        LocalDate monthEnd = monthEnd(candidate);
        User staff = candidate.getUser();
        Teacher teacher = candidate.getTeacher();
        List<Long> bonusIds = staff == null ? List.of()
            : calculator.pendingBonuses(staff, monthEnd).stream().map(BonusPenalty::getId).toList();

        BillingLocks.Locked locked = locks.acquire(BillingLocks.Plan.of().payroll(id).bonuses(bonusIds));
        Payroll p = locked.payroll();
        requireDraft(p);
        List<BonusPenalty> bonuses = locked.bonuses().stream()
            .filter(b -> b.getStatus() == BonusPenaltyStatus.PENDING)
            .filter(b -> calculator.belongsTo(b, staff, teacher))
            .filter(b -> b.getEffectiveDate() == null || !b.getEffectiveDate().isAfter(monthEnd))
            .toList();

        PayrollCalculationDetails details = SalaryCalculationService.withBonuses(
            calculator.fromJson(p.getCalculationDetails()), bonuses, BonusPenaltyStatus.APPLIED.name());
        if (expectedNetSalary != null && expectedNetSalary.compareTo(details.net()) != 0) {
            throw new ConflictException("payroll.netChanged", expectedNetSalary, details.net())
                .withData(Map.of("netSalary", details.net(), "expectedNetSalary", expectedNetSalary,
                    "bonusPenaltyAdjustment", details.bonusPenalty()));
        }
        if (details.net().signum() < 0) {
            throw CodedException.badRequest("payroll.netNegative", details.net());
        }
        for (BonusPenalty b : bonuses) {
            b.setStatus(BonusPenaltyStatus.APPLIED);
            b.setAppliedToPayrollId(p.getId());
            bonusPenaltyRepository.save(b);
        }
        p.setCalculationDetails(calculator.toJson(details));
        p.setBonusPenaltyAdjustment(details.bonusPenalty());
        p.setNetSalary(details.net());
        p.setStatus(PayrollStatus.APPROVED);
        p.setApprovedAt(LocalDateTime.now(clock));
        p.setApprovedBy(currentUser());
        return toResponse(payrollRepository.save(p));
    }

    // ── PAY ─────────────────────────────────────────────────────────────

    /**
     * APPROVED → PAID, qulf ostida. Takror: kalit bir xil bo'lsa — o'sha javob (yangi kassa yozuvi
     * yo'q), aks holda 409 {@code payroll.alreadyPaid} (payroll-v2 §5.2).
     */
    @Transactional
    @Audited(action = AuditAction.PAYMENT, entity = "Payroll",
        summary = "'Oylik to''landi: ' + #result.userName + ' ' + #result.month + '/' + #result.year"
            + " + ' = ' + #result.netSalary",
        entityId = "#id", label = "#result.userName")
    public PayrollResponse markAsPaid(Long id, PayrollPayDto body, String idempotencyKeyHeader) {
        PayrollPayDto dto = body != null ? body : new PayrollPayDto();
        String key = idempotencyKeyHeader != null && !idempotencyKeyHeader.isBlank()
            ? idempotencyKeyHeader.trim()
            : (dto.getIdempotencyKey() != null && !dto.getIdempotencyKey().isBlank()
                ? dto.getIdempotencyKey().trim() : null);
        if (key != null && key.length() > IDEMPOTENCY_KEY_MAX) {
            throw CodedException.badRequest("payroll.idempotency.keyTooLong", IDEMPOTENCY_KEY_MAX);
        }
        findById(id);

        BillingLocks.Locked locked = locks.acquire(BillingLocks.Plan.of()
            .payroll(id)
            .cashRegister(dto.getCashRegisterId()));
        Payroll p = locked.payroll();
        if (p.getStatus() == PayrollStatus.PAID) {
            if (key != null && key.equals(p.getPayIdempotencyKey())) {
                return toResponse(p);
            }
            throw new ConflictException("payroll.alreadyPaid");
        }
        if (p.getStatus() != PayrollStatus.APPROVED) {
            throw new ConflictException("payroll.notApproved", p.getStatus());
        }

        PaymentMethod method = dto.getPaymentMethod() != null ? dto.getPaymentMethod() : PaymentMethod.CASH;
        BigDecimal net = nz(p.getNetSalary());
        LocalDate today = LocalDate.now(clock);
        User actor = currentUser();
        if (net.signum() > 0 && dto.getCashRegisterId() != null) {
            PaymentMethod cashMethod = resolveCashPaymentMethod(method, dto.getPaymentMethodForCash());
            CashTransaction tx = cashRegisterService.recordExpense(
                dto.getCashRegisterId(),
                net,
                cashMethod,
                "Oylik: " + staffName(p),
                "Oylik to'lovi (" + p.getMonth() + "/" + p.getYear() + ")",
                today,
                actor,
                null,
                p.getTeacher(),
                null,
                null,
                dto.getCashPart(),
                dto.getCardPart(),
                p.getId());
            p.setCashRegister(tx.getCashRegister());
            p.setCashTransactionId(tx.getId());
        }
        p.setStatus(PayrollStatus.PAID);
        p.setPaymentMethod(method);
        p.setPaymentDate(today);
        p.setPaidAt(LocalDateTime.now(clock));
        p.setPaidBy(actor);
        p.setPayIdempotencyKey(key);
        return toResponse(payrollRepository.save(p));
    }

    // ── CANCEL (SA) ─────────────────────────────────────────────────────

    /**
     * APPROVED/PAID → CANCELLED (faqat SUPER_ADMIN, sabab majburiy). Bonuslar PENDING ga qaytadi;
     * PAID bo'lsa kassa chiqimi REVERSAL bilan teskari yoziladi (payroll-v2 §5.3).
     */
    @Transactional
    @Audited(action = AuditAction.PAYMENT_CANCEL, entity = "Payroll",
        summary = "'Oylik bekor qilindi: ' + #result.userName + ' ' + #result.month + '/' + #result.year"
            + " + ' — ' + #result.cancelReason",
        entityId = "#id", label = "#result.userName")
    public PayrollResponse cancel(Long id, String reason) {
        if (!BillingAuth.hasAnyRole("SUPER_ADMIN")) {
            throw CodedException.forbidden("payroll.cancel.forbidden");
        }
        String why = reason != null ? reason.trim() : "";
        if (why.length() < 3 || why.length() > 500) {
            throw CodedException.badRequest("payroll.cancel.reasonRequired");
        }
        Payroll candidate = findById(id);
        requireCancellable(candidate);
        CashTransaction original = candidate.getStatus() == PayrollStatus.PAID ? paidExpense(candidate) : null;
        List<Long> bonusIds = bonusPenaltyRepository.findByAppliedToPayrollId(id).stream()
            .map(BonusPenalty::getId).toList();

        BillingLocks.Locked locked = locks.acquire(BillingLocks.Plan.of()
            .payroll(id)
            .bonuses(bonusIds)
            .cashRegister(original != null ? original.getCashRegister().getId() : null));
        Payroll p = locked.payroll();
        requireCancellable(p);

        if (original != null) {
            cashRegisterService.recordReversal(original,
                "Oylik bekor qilindi (" + p.getMonth() + "/" + p.getYear() + ") — " + why);
        }
        for (BonusPenalty b : locked.bonuses()) {
            if (b.getStatus() == BonusPenaltyStatus.APPLIED && id.equals(b.getAppliedToPayrollId())) {
                b.setStatus(BonusPenaltyStatus.PENDING);
                b.setAppliedToPayrollId(null);
                bonusPenaltyRepository.save(b);
            }
        }
        p.setStatus(PayrollStatus.CANCELLED);
        p.setCancelledAt(LocalDateTime.now(clock));
        p.setCancelledBy(currentUser());
        p.setCancelReason(why);
        return toResponse(payrollRepository.save(p));
    }

    /**
     * To'lov chiqimi: v2 da {@code cashTransactionId}; v1 da (bog'lanmagan) — izoh bo'yicha
     * yagona mos chiqim. Kassasiz to'lov — null (teskari yozadigan narsa yo'q).
     */
    private CashTransaction paidExpense(Payroll p) {
        if (p.getCashTransactionId() != null) {
            return cashTransactionRepository.findById(p.getCashTransactionId())
                .orElseThrow(() -> CodedException.badRequest("payroll.cancel.legacyCash"));
        }
        if (p.getCashRegister() == null) {
            return null;
        }
        if (p.getTeacher() == null) {
            throw CodedException.badRequest("payroll.cancel.legacyCash");
        }
        List<CashTransaction> matches = cashTransactionRepository.findPayrollTransactions(
            p.getTeacher().getId(), p.getCashRegister().getId(),
            "%Oylik to'lovi (" + p.getMonth() + "/" + p.getYear() + ")%");
        if (matches.size() != 1) {
            throw CodedException.badRequest("payroll.cancel.legacyCash");
        }
        return matches.get(0);
    }

    // ── yordamchilar ────────────────────────────────────────────────────

    /** Hisob natijasini DRAFT ga yozadi (ustunlar + tuzilgan JSON). */
    private void applyCalculation(Payroll p, SalaryCalculationDto calc) {
        PayrollCalculationDetails d = calc.getCalculationDetails();
        p.setTeacher(teacherRepository.findByUser_Id(p.getUser().getId()).orElse(null));
        BigDecimal fixed = nz(calc.getBaseSalary());
        // leaves-exams-contracts §3.2: deductions = |LEAVE_DEDUCTION|; allowances — o'zgaruvchan qism va
        // SUBSTITUTE_LESSONS (gross = FIXED + allowances − deductions, Σ lines = net)
        BigDecimal leave = nz(calc.getLeaveDeduction());
        p.setBasicSalary(fixed);
        p.setAllowances(d.gross().subtract(fixed).add(leave));
        p.setDeductions(leave);
        p.setBonusPenaltyAdjustment(d.bonusPenalty());
        p.setNetSalary(d.net());
        p.setPaidStudentCount(calc.getPaidStudentCount());
        p.setPaidStudentUnits(calc.getPaidStudentUnits());
        p.setSalaryRuleId(d.rule() != null ? d.rule().id() : null);
        p.setNewStudentCount(calc.getNewStudentCount());
        p.setKpiApplied(calc.getKpiApplied());
        p.setKpiAmount(calc.getKpiAmount());
        p.setCalculationDetails(calculator.toJson(d));
        p.setCalcVersion(PayrollCalculationDetails.VERSION);
    }

    private static void requireDraft(Payroll p) {
        if (p.getStatus() != PayrollStatus.DRAFT) {
            throw new ConflictException("payroll.notDraft", p.getStatus());
        }
    }

    private static void requireCancellable(Payroll p) {
        if (p.getStatus() == PayrollStatus.DRAFT) {
            throw new ConflictException("payroll.cancel.draft");
        }
        if (p.getStatus() == PayrollStatus.CANCELLED) {
            throw new ConflictException("payroll.alreadyCancelled");
        }
    }

    private static LocalDate monthEnd(Payroll p) {
        return YearMonth.of(p.getYear(), p.getMonth()).atEndOfMonth();
    }

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : userRepository.findByUsername(auth.getName()).orElse(null);
    }

    /**
     * Kassaga yoziladigan usul: paymentMethodForCash ustunlik qiladi (eski nomlar ham
     * tushuniladi), aks holda oylikning o'z usuli, u ham yo'q bo'lsa — naqd.
     */
    private static PaymentMethod resolveCashPaymentMethod(PaymentMethod paymentMethod, String paymentMethodForCash) {
        PaymentMethod override = PaymentMethod.parseOrNull(paymentMethodForCash);
        if (override != null) {
            return override;
        }
        return paymentMethod != null ? paymentMethod : PaymentMethod.CASH;
    }

    public Payroll findById(Long id) {
        return payrollRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Payroll", id));
    }

    private static String staffName(Payroll p) {
        if (p.getTeacher() != null) {
            return (p.getTeacher().getFirstName() + " " + p.getTeacher().getLastName()).trim();
        }
        return p.getUser() != null ? SalaryCalculationService.fullName(p.getUser()) : "";
    }

    private static String userLabel(User u) {
        return u == null ? null : u.getUsername();
    }

    private JsonNode detailsNode(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private PayrollResponse toResponse(Payroll p) {
        BigDecimal basic = p.getBasicSalary();
        BigDecimal gross = basic == null && p.getAllowances() == null ? null : nz(basic).add(nz(p.getAllowances()));
        return PayrollResponse.builder()
            .id(p.getId()).uuid(p.getUuid())
            .teacherId(p.getTeacher() != null ? p.getTeacher().getId() : null)
            .teacherName(staffName(p))
            .userId(p.getUser() != null ? p.getUser().getId() : null)
            .userName(p.getUser() != null ? SalaryCalculationService.fullName(p.getUser()) : null)
            .role(p.getUser() != null && p.getUser().getRole() != null ? p.getUser().getRole().name() : null)
            .month(p.getMonth()).year(p.getYear())
            .status(p.getStatus())
            .basicSalary(basic).allowances(p.getAllowances())
            .deductions(p.getDeductions())
            .grossSalary(gross)
            .bonusPenaltyAdjustment(p.getBonusPenaltyAdjustment())
            .netSalary(p.getNetSalary())
            .paidStudentCount(p.getPaidStudentCount())
            .paidStudentUnits(p.getPaidStudentUnits())
            .newStudentCount(p.getNewStudentCount())
            .kpiApplied(p.getKpiApplied())
            .kpiAmount(p.getKpiAmount())
            .calculationDetails(detailsNode(p.getCalculationDetails()))
            .calcVersion(p.getCalcVersion())
            .paymentDate(p.getPaymentDate())
            .paymentMethod(p.getPaymentMethod() != null ? p.getPaymentMethod().name() : null)
            .paymentMethodLabel(p.getPaymentMethod() != null ? p.getPaymentMethod().getLabel() : null)
            .paymentMethodIcon(p.getPaymentMethod() != null ? p.getPaymentMethod().getIcon() : null)
            .cashRegisterId(p.getCashRegister() != null ? p.getCashRegister().getId() : null)
            .cashRegisterName(p.getCashRegister() != null ? p.getCashRegister().getName() : null)
            .cashTransactionId(p.getCashTransactionId())
            .approvedAt(p.getApprovedAt()).approvedByName(userLabel(p.getApprovedBy()))
            .paidAt(p.getPaidAt()).paidByName(userLabel(p.getPaidBy()))
            .cancelledAt(p.getCancelledAt()).cancelledByName(userLabel(p.getCancelledBy()))
            .cancelReason(p.getCancelReason())
            .notes(p.getNotes())
            .createdByName(userLabel(p.getCreatedBy()))
            .createdAt(p.getCreatedAt())
            .updatedAt(p.getUpdatedAt())
            .build();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
