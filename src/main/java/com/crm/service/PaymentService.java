package com.crm.service;

import com.crm.dto.request.PaymentRequest;
import com.crm.dto.response.DebtorResponse;
import com.crm.dto.response.PaymentResponse;
import com.crm.entity.*;
import com.crm.entity.enums.IncomeCategory;
import com.crm.entity.enums.PaymentStatus;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
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

    @Transactional
    public PaymentResponse createPayment(PaymentRequest request) {
        Student student = studentRepository.findById(request.getStudentId())
            .orElseThrow(() -> new ResourceNotFoundException("Student", request.getStudentId()));

        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User receiver = userRepository.findByUsername(username).orElse(null);

        Payment payment = Payment.builder()
            .student(student)
            .amount(request.getAmount())
            .paymentDate(LocalDateTime.now())
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

            // Update next payment date
            studentGroupRepository.findByStudentIdAndGroupIdAndIsActiveTrue(
                    request.getStudentId(), request.getGroupId())
                .ifPresent(sg -> {
                    sg.setNextPaymentDate(sg.getNextPaymentDate().plusDays(30));
                    studentGroupRepository.save(sg);
                    payment.setStudentGroup(sg);
                });
        }

        Payment saved = paymentRepository.save(payment);

        // Auto-create income record
        Income income = Income.builder()
            .category(IncomeCategory.STUDENT_PAYMENT)
            .amount(request.getAmount())
            .payment(saved)
            .description("Student payment: " + student.getFirstName() + " " + student.getLastName())
            .incomeDate(LocalDate.now())
            .receivedBy(receiver)
            .build();
        incomeRepository.save(income);

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> getStudentPayments(Long studentId) {
        return paymentRepository.findByStudentIdOrderByPaymentDateDesc(studentId)
            .stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<DebtorResponse> getDebtors() {
        LocalDate today = LocalDate.now();
        return studentGroupRepository.findDebtors(today).stream()
            .map(sg -> {
                BigDecimal monthlyPrice = sg.getMonthlyPriceOverride() != null
                    ? sg.getMonthlyPriceOverride()
                    : sg.getGroup().getCourse().getMonthlyPrice();

                BigDecimal discounted = monthlyPrice;
                if (sg.getDiscountPercentage() != null && sg.getDiscountPercentage().compareTo(BigDecimal.ZERO) > 0) {
                    discounted = monthlyPrice.subtract(
                        monthlyPrice.multiply(sg.getDiscountPercentage()).divide(BigDecimal.valueOf(100)));
                }

                return DebtorResponse.builder()
                    .studentId(sg.getStudent().getId())
                    .studentName(sg.getStudent().getFirstName() + " " + sg.getStudent().getLastName())
                    .phone(sg.getStudent().getPhone())
                    .parentPhone(sg.getStudent().getParentPhone())
                    .groupId(sg.getGroup().getId())
                    .groupName(sg.getGroup().getGroupName())
                    .nextPaymentDate(sg.getNextPaymentDate())
                    .daysOverdue(ChronoUnit.DAYS.between(sg.getNextPaymentDate(), today))
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
            .paymentDate(p.getPaymentDate())
            .paymentMethod(p.getPaymentMethod())
            .status(p.getStatus())
            .periodFrom(p.getPeriodFrom())
            .periodTo(p.getPeriodTo())
            .description(p.getDescription())
            .createdAt(p.getCreatedAt())
            .build();
    }
}
