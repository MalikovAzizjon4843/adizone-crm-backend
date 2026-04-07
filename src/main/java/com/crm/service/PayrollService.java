package com.crm.service;

import com.crm.dto.request.PayrollRequest;
import com.crm.dto.response.PageResponse;
import com.crm.dto.response.PayrollResponse;
import com.crm.entity.Payroll;
import com.crm.entity.Teacher;
import com.crm.exception.DuplicateResourceException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.PayrollRepository;
import com.crm.repository.TeacherRepository;
import com.crm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PayrollService {

    private final PayrollRepository payrollRepository;
    private final TeacherRepository teacherRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public PageResponse<PayrollResponse> getAllPayroll(int page, int size, String status) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Payroll> p = (status == null || status.isBlank())
            ? payrollRepository.findAll(pageable)
            : payrollRepository.findByStatus(status, pageable);
        return PageResponse.<PayrollResponse>builder()
            .content(p.getContent().stream().map(this::toResponse).collect(Collectors.toList()))
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
        return payrollRepository.findByTeacherId(teacherId).stream()
            .map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional
    public PayrollResponse createPayroll(PayrollRequest request) {
        if (payrollRepository.findByTeacherIdAndMonthAndYear(
                request.getTeacherId(), request.getMonth(), request.getYear()).isPresent()) {
            throw new DuplicateResourceException(
                "Payroll already exists for this teacher for " + request.getMonth() + "/" + request.getYear());
        }

        Teacher teacher = teacherRepository.findById(request.getTeacherId())
            .orElseThrow(() -> new ResourceNotFoundException("Teacher", request.getTeacherId()));

        Payroll payroll = buildPayroll(new Payroll(), request, teacher);
        return toResponse(payrollRepository.save(payroll));
    }

    @Transactional
    public PayrollResponse updatePayroll(Long id, PayrollRequest request) {
        Payroll payroll = findById(id);
        Teacher teacher = teacherRepository.findById(request.getTeacherId())
            .orElseThrow(() -> new ResourceNotFoundException("Teacher", request.getTeacherId()));
        buildPayroll(payroll, request, teacher);
        return toResponse(payrollRepository.save(payroll));
    }

    @Transactional
    public void deletePayroll(Long id) {
        payrollRepository.delete(findById(id));
    }

    @Transactional
    public PayrollResponse markAsPaid(Long id, String paymentMethod) {
        Payroll p = findById(id);
        p.setStatus("PAID");
        if (paymentMethod != null && !paymentMethod.isBlank()) {
            p.setPaymentMethod(paymentMethod);
        }
        p.setPaymentDate(LocalDate.now());
        return toResponse(payrollRepository.save(p));
    }

    public Payroll findById(Long id) {
        return payrollRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Payroll", id));
    }

    private Payroll buildPayroll(Payroll p, PayrollRequest req, Teacher teacher) {
        p.setTeacher(teacher);
        p.setMonth(req.getMonth());
        p.setYear(req.getYear());
        p.setBasicSalary(req.getBasicSalary());
        p.setAllowances(req.getAllowances() != null ? req.getAllowances() : java.math.BigDecimal.ZERO);
        p.setDeductions(req.getDeductions() != null ? req.getDeductions() : java.math.BigDecimal.ZERO);
        java.math.BigDecimal net = req.getNetSalary();
        if (net == null && req.getBasicSalary() != null) {
            net = req.getBasicSalary()
                .add(p.getAllowances() != null ? p.getAllowances() : java.math.BigDecimal.ZERO)
                .subtract(p.getDeductions() != null ? p.getDeductions() : java.math.BigDecimal.ZERO);
        }
        p.setNetSalary(net);
        p.setPaymentDate(req.getPaymentDate());
        p.setPaymentMethod(req.getPaymentMethod() != null ? req.getPaymentMethod() : "BANK_TRANSFER");
        p.setStatus(req.getStatus() != null ? req.getStatus() : "PENDING");
        p.setNotes(req.getNotes());
        if (req.getCreatedById() != null) {
            p.setCreatedBy(userRepository.findById(req.getCreatedById())
                .orElseThrow(() -> new ResourceNotFoundException("User", req.getCreatedById())));
        }
        return p;
    }

    private PayrollResponse toResponse(Payroll p) {
        return PayrollResponse.builder()
            .id(p.getId()).uuid(p.getUuid())
            .teacherId(p.getTeacher().getId())
            .teacherName(p.getTeacher().getFirstName() + " " + p.getTeacher().getLastName())
            .month(p.getMonth()).year(p.getYear())
            .basicSalary(p.getBasicSalary()).allowances(p.getAllowances())
            .deductions(p.getDeductions()).netSalary(p.getNetSalary())
            .paymentDate(p.getPaymentDate()).paymentMethod(p.getPaymentMethod())
            .status(p.getStatus()).notes(p.getNotes())
            .createdByName(p.getCreatedBy() != null ? p.getCreatedBy().getUsername() : null)
            .createdAt(p.getCreatedAt()).build();
    }
}
