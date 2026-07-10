package com.crm.service;

import com.crm.dto.request.PaymentRequest;
import com.crm.dto.response.DebtorResponse;
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
    private final StudentPaymentLifecycleService studentPaymentLifecycleService;
    private final CashRegisterService cashRegisterService;

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

        Payment payment = Payment.builder()
            .student(student)
            .amount(request.getAmount())
            .discountAmount(discount)
            .receiptNumber(receipt)
            .paymentDate(payDate)
            .paymentMethod(request.getPaymentMethod())
            .status(PaymentStatus.PAID)
            .periodFrom(request.getPeriodFrom())
            .periodTo(request.getPeriodTo())
            .description(request.getDescription())
            .notes(request.getNotes())
            .receivedBy(receiver)
            .build();

        // Auto-set group from student's active enrollment
        if (payment.getGroup() == null && request.getGroupId() == null) {
            studentGroupRepository
                .findByStudentIdAndIsActiveTrue(request.getStudentId())
                .stream()
                .findFirst()
                .ifPresent(sg -> {
                    payment.setGroup(sg.getGroup());
                    payment.setStudentGroup(sg);
                });
        }

        Long effectiveGroupId = request.getGroupId();
        if (effectiveGroupId == null && payment.getGroup() != null) {
            effectiveGroupId = payment.getGroup().getId();
        } else if (effectiveGroupId != null) {
            final Long gid = effectiveGroupId;
            Group group = groupRepository.findById(gid)
                .orElseThrow(() -> new ResourceNotFoundException("Group", gid));
            payment.setGroup(group);

            studentGroupRepository.findByStudentIdAndGroupIdAndIsActiveTrue(
                    request.getStudentId(), gid)
                .ifPresent(sg -> {
                    if (sg.getNextPaymentDate() != null) {
                        sg.setNextPaymentDate(sg.getNextPaymentDate().plusDays(30));
                    }
                    studentGroupRepository.save(sg);
                    payment.setStudentGroup(sg);
                });
        }

        Payment saved = paymentRepository.save(payment);

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

        if (effectiveGroupId != null) {
            studentPaymentLifecycleService.onPaymentReceived(
                request.getStudentId(), effectiveGroupId, payDate);
        }

        return toResponse(saved);
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
    public List<DebtorResponse> getDebtors() {
        LocalDate today = LocalDate.now();
        YearMonth ym = YearMonth.from(today);
        LocalDate monthStart = ym.atDay(1);
        LocalDate monthEnd = ym.atEndOfMonth();

        return studentGroupRepository.findAllActiveEnrollments().stream()
            .filter(sg -> !"TRIAL".equals(sg.getPaymentStatus()))
            .filter(sg -> paymentRepository.countPaidForStudentGroupInPeriod(
                sg.getStudent().getId(), sg.getGroup().getId(), monthStart, monthEnd) == 0)
            .map(sg -> {
                BigDecimal monthlyPrice = sg.getMonthlyPriceOverride() != null
                    ? sg.getMonthlyPriceOverride()
                    : sg.getGroup().getCourse().getMonthlyPrice();

                BigDecimal discounted = monthlyPrice;
                if (sg.getDiscountPercentage() != null && sg.getDiscountPercentage().compareTo(BigDecimal.ZERO) > 0) {
                    discounted = monthlyPrice.subtract(
                        monthlyPrice.multiply(sg.getDiscountPercentage()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
                }

                LocalDate nextDue = sg.getNextPaymentDate() != null ? sg.getNextPaymentDate() : monthStart;
                long overdue = ChronoUnit.DAYS.between(nextDue, today);

                return DebtorResponse.builder()
                    .studentId(sg.getStudent().getId())
                    .studentName(sg.getStudent().getFirstName() + " " + sg.getStudent().getLastName())
                    .phone(sg.getStudent().getPhone())
                    .parentPhone(sg.getStudent().getParentPhone())
                    .groupId(sg.getGroup().getId())
                    .groupName(sg.getGroup().getGroupName())
                    .nextPaymentDate(nextDue)
                    .daysOverdue(Math.max(0, overdue))
                    .monthlyAmount(discounted)
                    .build();
            })
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

    @Transactional(readOnly = true)
    public Map<String, Object> getDebtorsEnhanced() {
        List<StudentGroup> activeGroups =
            studentGroupRepository.findAllActiveEnrollments();

        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);

        List<Map<String, Object>> debtors = new java.util.ArrayList<>();
        double totalDebt = 0;
        List<Map<String, Object>> overdueDebtors =
            new java.util.ArrayList<>();

        for (StudentGroup sg : activeGroups) {
            if ("TRIAL".equals(sg.getPaymentStatus())) continue;

            boolean hasPaid = paymentRepository
                .countPaidForStudentGroupInPeriod(
                    sg.getStudent().getId(),
                    sg.getGroup().getId(),
                    monthStart, today) > 0;

            if (!hasPaid) {
                BigDecimal monthlyPrice = sg.getMonthlyPriceOverride() != null
                    ? sg.getMonthlyPriceOverride()
                    : (sg.getGroup().getCourse() != null
                        ? sg.getGroup().getCourse().getMonthlyPrice()
                        : BigDecimal.ZERO);

                long daysOverdue = sg.getLastPaymentDate() != null
                    ? ChronoUnit.DAYS.between(
                        sg.getLastPaymentDate(), today)
                    : ChronoUnit.DAYS.between(
                        sg.getJoinDate() != null
                            ? sg.getJoinDate() : today, today);

                Map<String, Object> debtor = new LinkedHashMap<>();
                debtor.put("studentId",
                    sg.getStudent().getId());
                debtor.put("studentName",
                    sg.getStudent().getFirstName() + " " +
                    sg.getStudent().getLastName());
                debtor.put("phone", sg.getStudent().getPhone());
                debtor.put("groupId", sg.getGroup().getId());
                debtor.put("groupName",
                    sg.getGroup().getGroupName());
                debtor.put("monthlyAmount", monthlyPrice);
                debtor.put("daysOverdue", daysOverdue);
                debtor.put("paymentStatus",
                    sg.getPaymentStatus());
                debtor.put("lastPaymentDate",
                    sg.getLastPaymentDate());

                totalDebt += monthlyPrice.doubleValue();
                debtors.add(debtor);

                if (daysOverdue > 7) {
                    overdueDebtors.add(debtor);
                }
            }
        }

        // Sort by daysOverdue desc
        debtors.sort((a, b) ->
            Long.compare((Long) b.get("daysOverdue"),
                         (Long) a.get("daysOverdue")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalDebt", totalDebt);
        result.put("totalDebtors", debtors.size());
        result.put("overdueCount", overdueDebtors.size());
        result.put("overdueDebtors", overdueDebtors);
        result.put("allDebtors", debtors);

        return result;
    }
}
