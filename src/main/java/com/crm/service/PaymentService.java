package com.crm.service;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PaymentService {

    /** PERIOD_CHARGE izohidagi sana formati. */
    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final PaymentRepository paymentRepository;
    private final Messages messages;
    private final StudentRepository studentRepository;
    private final GroupRepository groupRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final IncomeRepository incomeRepository;
    private final UserRepository userRepository;
    private final CashRegisterService cashRegisterService;
    private final BonusPenaltyService bonusPenaltyService;
    private final PaymentScheduleService paymentScheduleService;
    private final BalanceTransactionService balanceTransactionService;
    private final EntityManager entityManager;

    @Transactional
    public PaymentResponse createPayment(PaymentRequest request) {
        Student student = studentRepository.findById(request.getStudentId())
            .orElseThrow(() -> new ResourceNotFoundException(
                messages.get("error.student.notFound", request.getStudentId())));

        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User receiver = userRepository.findByUsername(username).orElse(null);

        LocalDate payDate = request.getPaymentDate() != null ? request.getPaymentDate() : LocalDate.now();

        long seq = paymentRepository.count() + 1;
        String receipt = "RCP-" + String.format("%05d", seq);

        StudentGroup enrollment = resolveEnrollment(request.getStudentId(), request.getGroupId());
        Group group = enrollment != null ? enrollment.getGroup() : null;
        if (group == null && request.getGroupId() != null) {
            group = groupRepository.findById(request.getGroupId())
                .orElseThrow(() -> new ResourceNotFoundException(
                    messages.get("error.group.notFound", request.getGroupId())));
        }

        // Butun hisob shu yerda — frontend XOM ma'lumot yuboradi (gross, discount, useBalance).
        PaymentCalculation calc = calculate(
            student, request.getAmount(), request.getDiscountAmount(), request.getUseBalance());
        BigDecimal discount = calc.discount();
        BigDecimal payable = calc.payable();
        BigDecimal balanceUsed = calc.balanceUsed();
        BigDecimal cashAmount = calc.cashAmount();

        // Davr hisobi gross asosida — chegirma ham, balansdan qoplangan qism ham
        // to'lov sanaladi (o'quvchi baribir o'sha davrni oladi).
        PaymentPeriod period = resolvePaymentPeriod(student, enrollment, request, calc.gross());
        LocalDate periodStart = period.periodStart();
        LocalDate periodEnd = period.periodEnd();

        Payment payment = Payment.builder()
            .student(student)
            .group(group)
            .studentGroup(enrollment)
            .amount(calc.gross())
            .payableAmount(payable)
            .cashAmount(cashAmount)
            .balanceUsed(balanceUsed)
            .discountAmount(discount)
            .receiptNumber(receipt)
            .paymentDate(payDate)
            .paymentMethod(request.getPaymentMethod())
            .status(PaymentStatus.PAID)
            .periodStart(periodStart)
            .periodEnd(periodEnd)
            .description(request.getDescription())
            .notes(request.getNotes())
            .receivedBy(receiver)
            .build();

        Payment saved = paymentRepository.save(payment);

        writeLedgerForPayment(saved, enrollment, calc, period);

        if (shouldApplyBonuses(request)) {
            BigDecimal bpNet = bonusPenaltyService.applyPendingForStudent(
                student.getId(), saved.getId(), saved.getPaymentDate());
            BigDecimal bonusDiscount = bpNet.max(BigDecimal.ZERO);
            saved.setBonusDiscount(bonusDiscount);
            BigDecimal totalDiscount = (saved.getDiscountAmount() != null
                ? saved.getDiscountAmount() : BigDecimal.ZERO).add(bonusDiscount);
            saved.setDiscountAmount(totalDiscount);
            if (bpNet.compareTo(BigDecimal.ZERO) < 0) {
                String penaltyNote = "Jarima qo'llandi: " + bpNet.abs().toPlainString();
                saved.setNotes(appendNote(saved.getNotes(), penaltyNote));
            }
            saved = paymentRepository.save(saved);
        }

        if (cashAmount.compareTo(BigDecimal.ZERO) > 0) {
            Income income = Income.builder()
                .category(IncomeCategory.STUDENT_PAYMENT)
                .amount(cashAmount)
                .payment(saved)
                .description("Student payment: " + student.getFirstName() + " " + student.getLastName())
                .incomeDate(payDate)
                .receivedBy(receiver)
                .build();
            incomeRepository.save(income);

            if (request.getCashRegisterId() != null) {
                PaymentMethod cashMethod = resolveCashPaymentMethod(request);
                CashTransaction cashTx = cashRegisterService.recordIncome(
                    request.getCashRegisterId(),
                    cashAmount,
                    cashMethod,
                    student,
                    "O'quvchi to'lovi",
                    "To'lov #" + saved.getReceiptNumber(),
                    saved.getPaymentDate(),
                    request.getCashPart(),
                    request.getCardPart());
                saved.setCashRegister(cashTx.getCashRegister());
                saved = paymentRepository.save(saved);
            }
        } else if (balanceUsed.compareTo(BigDecimal.ZERO) > 0) {
            String note = "To'liq balansdan qoplandı: " + balanceUsed.toPlainString();
            saved.setNotes(appendNote(saved.getNotes(), note));
            saved = paymentRepository.save(saved);
        }

        // PER_LESSON: periodEnd taxminiy — lessons purchased asosida
        if (enrollment != null && enrollment.getPaymentType() == PaymentType.PER_LESSON) {
            applyPerLessonPaymentPeriod(saved, enrollment, payable);
            saved = paymentRepository.save(saved);
        }

        paymentRepository.flush();
        paymentScheduleService.recalculateForStudent(student);

        return toResponse(saved);
    }

    /**
     * Balans daftariga ikki tomonlama yozuv.
     *
     * <pre>
     * KREDIT (ikkala tur uchun): PAYMENT, amount = cashAmount — kassaga tushgan REAL pul.
     *     Chegirma pul emas, shuning uchun kreditga kirmaydi.
     * DEBET (faqat MONTHLY):     PERIOD_CHARGE, amount = -(months x monthlyFee - discount).
     *     Chegirma bu yerda ayriladi — natijada u balansga NEYTRAL bo'ladi
     *     ({@link PeriodChargeFormula}). PER_LESSON da debet davomat orqali
     *     keladi (LESSON_CHARGE), bu yerda yozilmaydi.
     * </pre>
     */
    private void writeLedgerForPayment(Payment saved, StudentGroup enrollment,
                                       PaymentCalculation calc, PaymentPeriod period) {
        if (enrollment == null) {
            return;
        }

        BigDecimal balanceUsed = calc.balanceUsed();

        // 1. KREDIT — cashAmount 0 bo'lsa ham audit uchun yozuv qoldiriladi
        String payNote = "To'lov #" + saved.getReceiptNumber();
        if (balanceUsed.compareTo(BigDecimal.ZERO) > 0) {
            payNote += " (balansdan qoplandi: " + balanceUsed.toPlainString() + ")";
        }
        balanceTransactionService.record(
            enrollment,
            BalanceTransactionType.PAYMENT,
            calc.cashAmount(),
            saved.getId(),
            payNote);

        // 2. DEBET — faqat MONTHLY, faqat to'liq oy(lar) sotib olinganda
        PaymentType paymentType = enrollment.getPaymentType() != null
            ? enrollment.getPaymentType()
            : PaymentType.MONTHLY;
        if (paymentType != PaymentType.MONTHLY) {
            return;
        }

        int months = period.chargeMonths();
        BigDecimal debit = PeriodChargeFormula.debit(months, calc.discount(), period.monthlyFee());
        if (debit.compareTo(BigDecimal.ZERO) <= 0) {
            // gross < monthlyFee (davr sotib olinmadi) yoki chegirma davr qiymatini
            // to'liq qopladi — ikkala holda ham debet yozilmaydi.
            return;
        }

        String chargeNote = "Davr sotib olindi: " + formatDate(period.periodStart())
            + " – " + formatDate(period.periodEnd())
            + " (" + months + " oy)";
        if (calc.discount().compareTo(BigDecimal.ZERO) > 0) {
            chargeNote += ", chegirma: " + calc.discount().toPlainString();
        }

        balanceTransactionService.record(
            enrollment,
            BalanceTransactionType.PERIOD_CHARGE,
            debit.negate(),
            saved.getId(),
            chargeNote);
    }

    private static String formatDate(LocalDate date) {
        return date != null ? date.format(PERIOD_FMT) : "-";
    }

    private void applyPerLessonPaymentPeriod(Payment payment, StudentGroup sg, BigDecimal payable) {
        BigDecimal lessonPrice = PaymentScheduleService.resolveLessonPrice(sg);
        if (lessonPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        int bought = payable.divide(lessonPrice, 0, RoundingMode.DOWN).intValue();
        if (bought < 1) {
            bought = 1;
        }
        LocalDate start = payment.getPeriodStart() != null ? payment.getPeriodStart() : LocalDate.now();
        payment.setPeriodStart(start);
        // periodEnd: taxminiy — dars kunlari bo'yicha (recalc aniqroq yangilaydi)
        payment.setPeriodEnd(start.plusDays(Math.max(bought, 1)));
    }

    /** To'lov hisobining natijasi — createPayment va preview bir xil qiymatlardan foydalanadi. */
    public record PaymentCalculation(
        BigDecimal gross,
        BigDecimal discount,
        BigDecimal payable,
        BigDecimal balanceUsed,
        BigDecimal cashAmount,
        BigDecimal studentBalance,
        BigDecimal balanceAfter) {}

    /**
     * To'lov hisobining YAGONA manbai. Frontend hisoblagan qiymatlarga ishonilmaydi:
     * balans miqdori bu yerda o'quvchining haqiqiy balansidan olinadi.
     *
     * <pre>
     * payable     = gross - discount
     * balanceUsed = useBalance ? min(max(balance, 0), payable) : 0
     * cashAmount  = payable - balanceUsed
     * </pre>
     */
    private PaymentCalculation calculate(Student student, BigDecimal amount,
                                         BigDecimal discountAmount, Boolean useBalance) {
        // Manfiy yoki null summa 0 deb olinadi — bu yerda 500 chiqmasligi kerak.
        BigDecimal gross = nz(amount).max(BigDecimal.ZERO);
        BigDecimal discount = nz(discountAmount).max(BigDecimal.ZERO);
        if (discount.compareTo(gross) > 0) {
            throw new IllegalArgumentException(messages.get("payment.discount.tooLarge"));
        }

        BigDecimal payable = gross.subtract(discount);

        BigDecimal studentBalance = nz(student.getBalance());
        BigDecimal balanceUsed = BigDecimal.ZERO;
        if (Boolean.TRUE.equals(useBalance)) {
            BigDecimal avail = studentBalance.max(BigDecimal.ZERO);
            balanceUsed = avail.min(payable);
        }

        BigDecimal cashAmount = payable.subtract(balanceUsed);

        return new PaymentCalculation(gross, discount, payable, balanceUsed, cashAmount,
            studentBalance, studentBalance.subtract(balanceUsed));
    }

    /** Dry-run: hech narsa saqlanmaydi, createPayment bilan bir xil formula. */
    @Transactional(readOnly = true)
    public PaymentPreviewResponse previewPayment(PaymentPreviewRequest request) {
        if (request.getStudentId() == null) {
            throw new BadRequestException(messages.get("payment.student.required"));
        }
        Student student = studentRepository.findById(request.getStudentId())
            .orElseThrow(() -> new ResourceNotFoundException(
                messages.get("error.student.notFound", request.getStudentId())));

        // groupId hisobga ta'sir qilmaydi — balans o'quvchi darajasida yuritiladi,
        // shuning uchun guruhsiz ham to'g'ri ishlaydi.
        PaymentCalculation calc = calculate(
            student, request.getAmount(), request.getDiscountAmount(), request.getUseBalance());

        return PaymentPreviewResponse.builder()
            .gross(calc.gross())
            .discount(calc.discount())
            .payable(calc.payable())
            .balanceUsed(calc.balanceUsed())
            .cashAmount(calc.cashAmount())
            .studentBalance(calc.studentBalance())
            .balanceAfter(calc.balanceAfter())
            .build();
    }

    private StudentGroup resolveEnrollment(Long studentId, Long groupId) {
        if (groupId != null) {
            return studentGroupRepository
                .findByStudentIdAndGroupIdAndIsActiveTrue(studentId, groupId)
                .orElseGet(() -> studentGroupRepository.findActiveByStudentId(studentId)
                    .stream().findFirst().orElse(null));
        }
        return studentGroupRepository.findActiveByStudentId(studentId)
            .stream().findFirst().orElse(null);
    }

    /**
     * To'lov davri va uning ledger qiymati.
     *
     * <p>{@code chargeMonths} — PERIOD_CHARGE debiti uchun HAQIQIY to'langan oylar soni,
     * u {@code periodEnd} dagi kabi 1 ga clamp QILINMAYDI. Ikkalasi ataylab ajratilgan:
     * {@code periodEnd} → nextPaymentDate zanjiri (eski xatti-harakat saqlanadi),
     * {@code chargeMonths} → balans daftari. payable &lt; monthlyFee bo'lsa chargeMonths=0,
     * ya'ni davr sotib olinmagan va pul balansda qoladi.
     */
    private record PaymentPeriod(
        LocalDate periodStart,
        LocalDate periodEnd,
        int chargeMonths,
        BigDecimal monthlyFee) {}

    /**
     * <pre>
     * periodStart  = request.periodFrom ?? sg.nextPaymentDate ?? sg.paymentStartDate ?? sg.joinDate
     * chargeMonths = floor(gross / monthlyFee)          // 0 bo'lishi mumkin — ledger uchun
     * periodEnd    = periodStart + max(chargeMonths, 1) - 1 kun
     * </pre>
     * Davr qo'lda berilgan bo'lsa (periodFrom + periodTo) chargeMonths o'sha davrdan olinadi.
     */
    private PaymentPeriod resolvePaymentPeriod(Student student, StudentGroup sg, PaymentRequest request,
                                               BigDecimal gross) {
        LocalDate periodStart = request.getPeriodFrom();
        LocalDate periodEnd = request.getPeriodTo();

        if (periodStart == null) {
            if (sg != null && sg.getNextPaymentDate() != null) {
                periodStart = sg.getNextPaymentDate();
            } else if (student.getNextPaymentDate() != null) {
                periodStart = student.getNextPaymentDate();
            } else if (sg != null && sg.getPaymentStartDate() != null) {
                periodStart = sg.getPaymentStartDate();
            } else if (student.getPaymentStartDate() != null) {
                periodStart = student.getPaymentStartDate();
            } else if (sg != null && sg.getJoinDate() != null) {
                periodStart = sg.getJoinDate();
            } else {
                periodStart = LocalDate.now();
            }
        }

        BigDecimal fee = student.getMonthlyFee();
        if ((fee == null || fee.compareTo(BigDecimal.ZERO) <= 0) && sg != null) {
            fee = PaymentScheduleService.resolveMonthlyFee(sg);
        }
        fee = nz(fee);

        BigDecimal credit = gross != null ? gross : request.getAmount();
        int chargeMonths = PeriodChargeFormula.months(credit, fee);

        if (periodEnd == null) {
            // periodEnd eski qoida bo'yicha: kamida 1 oy (nextPaymentDate zanjiri o'zgarmasin)
            periodEnd = periodStart.plusMonths(Math.max(chargeMonths, 1)).minusDays(1);
        } else if (request.getPeriodTo() != null && request.getPeriodFrom() != null) {
            // Davr qo'lda berilgan — debet ham o'sha davrga mos bo'lsin
            chargeMonths = (int) ChronoUnit.MONTHS.between(periodStart, periodEnd.plusDays(1));
            if (chargeMonths < 0) {
                chargeMonths = 0;
            }
        }

        return new PaymentPeriod(periodStart, periodEnd, chargeMonths, fee);
    }

    @Transactional(readOnly = true)
    public List<SuspendedStudentResponse> getArchivedSuspendedStudents() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(3);
        return studentGroupRepository.findSuspendedOnOrBefore(cutoff).stream()
            .map(sg -> SuspendedStudentResponse.builder()
                .studentId(sg.getStudent().getId())
                .studentName(sg.getStudent().getFirstName() + " " + sg.getStudent().getLastName())
                .groupId(sg.getGroup().getId())
                .groupName(sg.getGroup().getGroupName())
                .suspendedAt(sg.getSuspendedAt())
                .suspensionReason(sg.getSuspensionReason())
                .daysSinceSuspended(sg.getSuspendedAt() != null
                    ? ChronoUnit.DAYS.between(sg.getSuspendedAt().toLocalDate(), LocalDate.now()) : null)
                .build())
            .collect(Collectors.toList());
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
        return summarize(buildPaymentSpec(studentId, groupId, status, from, to));
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
    public List<PaymentResponse> getAllPayments(LocalDate from, LocalDate to) {
        List<Payment> list;
        if (from != null && to != null) {
            list = paymentRepository.findByDateRange(from, to);
        } else {
            list = paymentRepository.findAll(Sort.by(Sort.Direction.DESC, "paymentDate"));
        }
        return list.stream().map(this::toResponse).collect(Collectors.toList());
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
        stats.put("totalPending", nz(paymentRepository.sumAmountByStatus(PaymentStatus.PENDING)));
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

    @Transactional(readOnly = true)
    public DebtorsListResponse getDebtors() {
        return paymentScheduleService.getDebtorsByDate();
    }

    @Transactional(readOnly = true)
    public ExpectedPaymentsResponse getExpectedPayments(LocalDate from, LocalDate to) {
        return paymentScheduleService.getExpectedPayments(from, to);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getDebtorsSummary() {
        return paymentScheduleService.getDebtorsSummary();
    }

    /** Legacy list for Telegram reminders. */
    @Transactional(readOnly = true)
    public List<DebtorResponse> getDebtorsLegacy() {
        return paymentScheduleService.getDebtorsByDate().getStudents().stream()
            .map(d -> DebtorResponse.builder()
                .studentId(d.getStudentId())
                .studentName(d.getFullName())
                .phone(d.getPhone())
                .groupName(d.getGroupName())
                .nextPaymentDate(d.getNextPaymentDate())
                .daysOverdue(d.getDaysOverdue())
                .monthlyAmount(d.getAmount())
                .build())
            .collect(Collectors.toList());
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
            .build();
    }

    /**
     * Kassaga yoziladigan usul. Endi to'lovning haqiqiy usuli saqlanadi (CLICK, PAYME, ...) —
     * ilgari hammasi PLASTIC ga aylanardi. Naqd/plastik balans taqsimotini
     * CashRegisterService o'zi enum bo'yicha hal qiladi.
     */
    private static PaymentMethod resolveCashPaymentMethod(PaymentRequest request) {
        if (request.getPaymentMethodForCash() != null
                && !request.getPaymentMethodForCash().isBlank()) {
            PaymentMethod override = PaymentMethod.parseOrNull(request.getPaymentMethodForCash());
            if (override == null) {
                throw new BadRequestException(messages.get(
                    "payment.methodForCash.invalid", request.getPaymentMethodForCash()));
            }
            return override;
        }
        return request.getPaymentMethod() != null
            ? request.getPaymentMethod() : PaymentMethod.CASH;
    }

    private static boolean shouldApplyBonuses(PaymentRequest request) {
        return request.getApplyBonuses() == null || Boolean.TRUE.equals(request.getApplyBonuses());
    }

    private static String appendNote(String existing, String addition) {
        if (addition == null || addition.isBlank()) {
            return existing;
        }
        if (existing == null || existing.isBlank()) {
            return addition;
        }
        return existing + "\n" + addition;
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

    public Map<String, Object> calculateStudentDebt(
            Long studentId, Long groupId) {

        Map<String, Object> result = new LinkedHashMap<>();

        // Find student group
        StudentGroup sg = studentGroupRepository
            .findByStudentIdAndGroupIdAndIsActiveTrue(
                studentId, groupId)
            .orElse(null);

        if (sg == null) {
            result.put("debt", 0);
            result.put("message", "Guruh topilmadi");
            return result;
        }

        Group group = sg.getGroup();
        BigDecimal monthlyPrice = sg.getMonthlyPriceOverride() != null
            ? sg.getMonthlyPriceOverride()
            : (group.getCourse() != null
                ? group.getCourse().getMonthlyPrice()
                : BigDecimal.ZERO);

        // Calculate days since join
        LocalDate joinDate = sg.getJoinDate() != null
            ? sg.getJoinDate() : LocalDate.now();
        LocalDate today = LocalDate.now();

        long daysSinceJoin = ChronoUnit.DAYS
            .between(joinDate, today);

        // Total should pay
        double totalShouldPay =
            (daysSinceJoin / 30.0) * monthlyPrice.doubleValue();

        // Total paid
        BigDecimal totalPaid = paymentRepository
            .sumPaidByStudentAndGroup(studentId, groupId);
        if (totalPaid == null) totalPaid = BigDecimal.ZERO;

        double debt = Math.max(0, totalShouldPay - totalPaid.doubleValue());

        result.put("studentId", studentId);
        result.put("groupId", groupId);
        result.put("joinDate", joinDate);
        result.put("daysSinceJoin", daysSinceJoin);
        result.put("monthlyPrice", monthlyPrice);
        result.put("totalShouldPay", Math.round(totalShouldPay));
        result.put("totalPaid", totalPaid);
        result.put("debt", Math.round(debt));
        result.put("message", debt > 0
            ? String.format("%.0f kun uchun %.0f UZS qarzdorlik",
                (double) daysSinceJoin, debt)
            : "Qarzdorlik yo'q");

        return result;
    }

}
