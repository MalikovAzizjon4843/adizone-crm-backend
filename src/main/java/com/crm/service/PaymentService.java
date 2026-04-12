package com.crm.service;

import com.crm.dto.request.PaymentRequest;
import com.crm.dto.response.DebtorResponse;
import com.crm.dto.response.PaymentHistoryResponse;
import com.crm.dto.response.PaymentResponse;
import com.crm.dto.response.SuspendedStudentResponse;
import com.crm.entity.*;
import com.crm.entity.enums.IncomeCategory;
import com.crm.entity.enums.PaymentStatus;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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

        if (request.getGroupId() != null) {
            Group group = groupRepository.findById(request.getGroupId())
                .orElseThrow(() -> new ResourceNotFoundException("Group", request.getGroupId()));
            payment.setGroup(group);

            studentGroupRepository.findByStudentIdAndGroupIdAndIsActiveTrue(
                    request.getStudentId(), request.getGroupId())
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

        if (request.getGroupId() != null) {
            studentPaymentLifecycleService.onPaymentReceived(
                request.getStudentId(), request.getGroupId(), payDate);
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
            int page,
            int size,
            Long studentId,
            Long groupId,
            String status,
            LocalDate from,
            LocalDate to) {
        PaymentStatus st = null;
        if (status != null && !status.isBlank()) {
            try {
                st = PaymentStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // leave null → no status filter
            }
        }
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "paymentDate"));
        Page<Payment> p = paymentRepository.searchPayments(studentId, groupId, st, from, to, pageable);
        return p.map(this::toResponse);
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
}
