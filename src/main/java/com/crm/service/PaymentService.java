package com.crm.service;

import com.crm.dto.request.PaymentRequest;
import com.crm.dto.response.DebtorResponse;
import com.crm.dto.response.DebtorsListResponse;
import com.crm.dto.response.ExpectedPaymentsResponse;
import com.crm.dto.response.PaymentHistoryResponse;
import com.crm.dto.response.PaymentResponse;
import com.crm.dto.response.SuspendedStudentResponse;
import com.crm.entity.*;
import com.crm.entity.enums.CashPaymentMethod;
import com.crm.entity.enums.IncomeCategory;
import com.crm.entity.enums.PaymentMethod;
import com.crm.entity.enums.PaymentStatus;
import com.crm.exception.BadRequestException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.*;
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
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final StudentRepository studentRepository;
    private final GroupRepository groupRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final IncomeRepository incomeRepository;
    private final UserRepository userRepository;
    private final CashRegisterService cashRegisterService;
    private final BonusPenaltyService bonusPenaltyService;
    private final PaymentScheduleService paymentScheduleService;

    @Transactional
    public PaymentResponse createPayment(PaymentRequest request) {
        Student student = studentRepository.findById(request.getStudentId())
            .orElseThrow(() -> new ResourceNotFoundException("Student", request.getStudentId()));

        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User receiver = userRepository.findByUsername(username).orElse(null);

        BigDecimal discount = request.getDiscountAmount() != null
            ? request.getDiscountAmount() : BigDecimal.ZERO;
        if (discount.compareTo(BigDecimal.ZERO) < 0) {
            discount = BigDecimal.ZERO;
        }

        LocalDate payDate = request.getPaymentDate() != null ? request.getPaymentDate() : LocalDate.now();

        long seq = paymentRepository.count() + 1;
        String receipt = "RCP-" + String.format("%05d", seq);

        // Resolve StudentGroup FIRST — periodStart shundan olinadi
        StudentGroup enrollment = resolveEnrollment(request.getStudentId(), request.getGroupId());
        Group group = enrollment != null ? enrollment.getGroup() : null;
        if (group == null && request.getGroupId() != null) {
            group = groupRepository.findById(request.getGroupId())
                .orElseThrow(() -> new ResourceNotFoundException("Group", request.getGroupId()));
        }

        LocalDate[] period = resolvePaymentPeriod(student, enrollment, request);
        LocalDate periodStart = period[0];
        LocalDate periodEnd = period[1];

        Payment payment = Payment.builder()
            .student(student)
            .group(group)
            .studentGroup(enrollment)
            .amount(request.getAmount())
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

        Income income = Income.builder()
            .category(IncomeCategory.STUDENT_PAYMENT)
            .amount(request.getAmount())
            .payment(saved)
            .description("Student payment: " + student.getFirstName() + " " + student.getLastName())
            .incomeDate(payDate)
            .receivedBy(receiver)
            .build();
        incomeRepository.save(income);

        if (request.getCashRegisterId() != null) {
            CashPaymentMethod cashMethod = resolveCashPaymentMethod(request);
            CashTransaction cashTx = cashRegisterService.recordIncome(
                request.getCashRegisterId(),
                saved.getAmount(),
                cashMethod,
                student,
                "O'quvchi to'lovi",
                "To'lov #" + saved.getReceiptNumber(),
                saved.getPaymentDate());
            saved.setCashRegister(cashTx.getCashRegister());
            saved = paymentRepository.save(saved);
        }

        paymentRepository.flush();
        paymentScheduleService.recalculateForStudent(student);

        return toResponse(saved);
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
     * periodStart = sg.nextPaymentDate ?? sg.paymentStartDate ?? sg.joinDate
     * months = max(1, amount / monthlyFee) when fee &gt; 0
     * periodEnd = periodStart + months - 1 day
     */
    private LocalDate[] resolvePaymentPeriod(Student student, StudentGroup sg, PaymentRequest request) {
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

        if (periodEnd == null) {
            BigDecimal fee = student.getMonthlyFee();
            if ((fee == null || fee.compareTo(BigDecimal.ZERO) <= 0) && sg != null) {
                fee = PaymentScheduleService.resolveMonthlyFee(sg);
            }
            int months = 1;
            if (fee != null && fee.compareTo(BigDecimal.ZERO) > 0 && request.getAmount() != null) {
                months = request.getAmount().divide(fee, 0, RoundingMode.DOWN).intValue();
                if (months < 1) {
                    months = 1;
                }
            }
            periodEnd = periodStart.plusMonths(months).minusDays(1);
        }

        return new LocalDate[] { periodStart, periodEnd };
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

        LocalDate fromDate = (from == null || from.isBlank()) ? null : LocalDate.parse(from);
        LocalDate toDate = (to == null || to.isBlank()) ? null : LocalDate.parse(to);

        PaymentStatus statusEnum = null;
        if (status != null && !status.isBlank()) {
            try {
                statusEnum = PaymentStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                statusEnum = null;
            }
        }

        final Long sId = studentId;
        final Long gId = groupId;
        final PaymentStatus st = statusEnum;
        final LocalDate fd = fromDate;
        final LocalDate td = toDate;

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

        return paymentRepository.findAll(spec, pageable).map(this::toResponse);
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
        stats.put("totalCollected", nz(paymentRepository.sumAmountByStatus(PaymentStatus.PAID)));
        stats.put("totalPending", nz(paymentRepository.sumAmountByStatus(PaymentStatus.PENDING)));
        stats.put("thisMonth", nz(paymentRepository.sumPaidBetween(monthStart, monthEnd)));
        stats.put("lastMonth", nz(paymentRepository.sumPaidBetween(prevStart, prevEnd)));
        return stats;
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

    private static CashPaymentMethod resolveCashPaymentMethod(PaymentRequest request) {
        if (request.getPaymentMethodForCash() != null
                && !request.getPaymentMethodForCash().isBlank()) {
            try {
                return CashPaymentMethod.valueOf(
                    request.getPaymentMethodForCash().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new BadRequestException(
                    "Noto'g'ri paymentMethodForCash: " + request.getPaymentMethodForCash());
            }
        }
        PaymentMethod pm = request.getPaymentMethod() != null
            ? request.getPaymentMethod() : PaymentMethod.CASH;
        return pm == PaymentMethod.CASH ? CashPaymentMethod.CASH : CashPaymentMethod.PLASTIC;
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
