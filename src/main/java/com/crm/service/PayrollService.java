package com.crm.service;

import com.crm.dto.request.PayrollPayDto;
import com.crm.dto.request.PayrollRequest;
import com.crm.dto.response.PageResponse;
import com.crm.dto.response.PayrollResponse;
import com.crm.dto.response.SalaryCalculationDto;
import com.crm.entity.CashTransaction;
import com.crm.entity.Payroll;
import com.crm.entity.Teacher;
import com.crm.entity.User;
import com.crm.entity.enums.CashPaymentMethod;
import com.crm.exception.BadRequestException;
import com.crm.exception.DuplicateResourceException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.CashTransactionRepository;
import com.crm.repository.PayrollRepository;
import com.crm.repository.TeacherRepository;
import com.crm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PayrollService {

    private final PayrollRepository payrollRepository;
    private final TeacherRepository teacherRepository;
    private final UserRepository userRepository;
    private final CashRegisterService cashRegisterService;
    private final BonusPenaltyService bonusPenaltyService;
    private final CashTransactionRepository cashTransactionRepository;
    private final SalaryCalculationService salaryCalculationService;

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

    @Transactional(readOnly = true)
    public List<SalaryCalculationDto> previewCalculate(int month, int year) {
        return salaryCalculationService.calculateAll(month, year);
    }

    @Transactional(readOnly = true)
    public SalaryCalculationDto previewCalculateUser(Long userId, int month, int year) {
        return salaryCalculationService.calculateForUser(userId, month, year);
    }

    @Transactional
    public Map<String, Object> generatePayroll(int month, int year, boolean overwrite) {
        List<Payroll> existing = payrollRepository.findByPeriod(year, month);
        if (!existing.isEmpty() && !overwrite) {
            throw new BadRequestException("Bu oy uchun oylik yaratilgan");
        }

        List<SalaryCalculationDto> calcs = salaryCalculationService.calculateAll(month, year);
        int created = 0;
        int skipped = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;
        LocalDate periodEnd = YearMonth.of(year, month).atEndOfMonth();

        for (SalaryCalculationDto calc : calcs) {
            if (!Boolean.TRUE.equals(calc.getCalculable()) || calc.getUserId() == null) {
                skipped++;
                continue;
            }

            User user = userRepository.findById(calc.getUserId()).orElse(null);
            if (user == null) {
                skipped++;
                continue;
            }

            Payroll payroll = payrollRepository.findByUser_IdAndMonthAndYear(user.getId(), month, year)
                .orElse(null);

            if (payroll != null && "PAID".equalsIgnoreCase(payroll.getStatus())) {
                skipped++;
                continue;
            }

            if (payroll == null) {
                payroll = new Payroll();
                payroll.setMonth(month);
                payroll.setYear(year);
                payroll.setStatus("PENDING");
                payroll.setPaymentMethod("BANK_TRANSFER");
            } else if (!overwrite) {
                skipped++;
                continue;
            }

            Teacher teacher = teacherRepository.findByUser_Id(user.getId()).orElse(null);
            payroll.setUser(user);
            payroll.setTeacher(teacher);
            payroll.setBasicSalary(calc.getBaseSalary() != null ? calc.getBaseSalary() : BigDecimal.ZERO);
            payroll.setAllowances(nz(calc.getPerStudentAmount()).add(nz(calc.getNewStudentAmount())).add(nz(calc.getKpiAmount())));
            payroll.setDeductions(BigDecimal.ZERO);
            payroll.setPaidStudentCount(calc.getPaidStudentCount());
            payroll.setNewStudentCount(calc.getNewStudentCount());
            payroll.setKpiApplied(calc.getKpiApplied());
            payroll.setKpiAmount(calc.getKpiAmount());
            payroll.setCalculationDetails(salaryCalculationService.toDetailsJson(calc));
            payroll.setCreatedBy(currentUser());
            payroll.setStatus("PENDING");

            BigDecimal netBeforeBp = nz(calc.getTotalAmount()).subtract(nz(calc.getBonusPenaltyAdjustment()));
            payroll.setNetSalary(netBeforeBp);
            payroll.setBonusPenaltyAdjustment(BigDecimal.ZERO);

            Payroll saved = payrollRepository.save(payroll);

            BigDecimal adjustment = BigDecimal.ZERO;
            if (teacher != null) {
                adjustment = bonusPenaltyService.applyPendingForTeacher(
                    teacher.getId(), saved.getId(), periodEnd);
            }
            saved.setBonusPenaltyAdjustment(adjustment);
            saved.setNetSalary(netBeforeBp.add(adjustment));
            saved = payrollRepository.save(saved);

            created++;
            if (saved.getNetSalary() != null) {
                totalAmount = totalAmount.add(saved.getNetSalary());
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("created", created);
        result.put("skipped", skipped);
        result.put("totalAmount", totalAmount);
        return result;
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
        if (teacher.getUser() != null) {
            payroll.setUser(teacher.getUser());
        }
        Payroll saved = payrollRepository.save(payroll);

        LocalDate periodEnd = YearMonth.of(saved.getYear(), saved.getMonth()).atEndOfMonth();
        BigDecimal adjustment = bonusPenaltyService.applyPendingForTeacher(
            teacher.getId(), saved.getId(), periodEnd);
        saved.setBonusPenaltyAdjustment(adjustment);
        if (saved.getNetSalary() != null) {
            saved.setNetSalary(saved.getNetSalary().add(adjustment));
        } else if (adjustment.compareTo(BigDecimal.ZERO) != 0) {
            saved.setNetSalary(adjustment);
        }

        return toResponse(payrollRepository.save(saved));
    }

    @Transactional
    public PayrollResponse updatePayroll(Long id, PayrollRequest request) {
        Payroll payroll;
        boolean isNew = false;
        try {
            payroll = findById(id);
        } catch (ResourceNotFoundException e) {
            payroll = new Payroll();
            isNew = true;
        }

        Teacher teacher = teacherRepository.findById(request.getTeacherId())
            .orElseThrow(() -> new ResourceNotFoundException("Teacher", request.getTeacherId()));

        if (isNew) {
            if (payrollRepository.findByTeacherIdAndMonthAndYear(
                    request.getTeacherId(), request.getMonth(), request.getYear()).isPresent()) {
                throw new DuplicateResourceException(
                    "Payroll already exists for this teacher for " + request.getMonth() + "/" + request.getYear());
            }
        }

        String oldStatus = payroll.getStatus();
        BigDecimal oldNetSalary = payroll.getNetSalary() != null ? payroll.getNetSalary() : BigDecimal.ZERO;

        buildPayroll(payroll, request, teacher);
        if (teacher.getUser() != null) {
            payroll.setUser(teacher.getUser());
        }

        BigDecimal basic = request.getBasicSalary() != null ? request.getBasicSalary() : BigDecimal.ZERO;
        BigDecimal allowances = request.getAllowances() != null ? request.getAllowances() : BigDecimal.ZERO;
        BigDecimal newNetSalaryBeforeAdjustment = basic.add(allowances);

        if (isNew) {
            payroll.setNetSalary(newNetSalaryBeforeAdjustment);
            Payroll saved = payrollRepository.save(payroll);
            LocalDate periodEnd = YearMonth.of(saved.getYear(), saved.getMonth()).atEndOfMonth();
            BigDecimal adjustment = bonusPenaltyService.applyPendingForTeacher(
                teacher.getId(), saved.getId(), periodEnd);
            saved.setBonusPenaltyAdjustment(adjustment);
            saved.setNetSalary(newNetSalaryBeforeAdjustment.add(adjustment));
            return toResponse(payrollRepository.save(saved));
        }

        BigDecimal adjustment = payroll.getBonusPenaltyAdjustment() != null
            ? payroll.getBonusPenaltyAdjustment() : BigDecimal.ZERO;
        BigDecimal finalNetSalary = newNetSalaryBeforeAdjustment.add(adjustment);
        payroll.setNetSalary(finalNetSalary);

        if ("PAID".equalsIgnoreCase(oldStatus) && finalNetSalary.compareTo(oldNetSalary) != 0) {
            if (payroll.getCashRegister() != null && payroll.getTeacher() != null) {
                String notePattern = "%Oylik to'lovi (" + payroll.getMonth() + "/" + payroll.getYear() + ")%";
                List<CashTransaction> txs = cashTransactionRepository.findPayrollTransactions(
                    payroll.getTeacher().getId(),
                    payroll.getCashRegister().getId(),
                    notePattern
                );
                for (CashTransaction tx : txs) {
                    cashRegisterService.deleteExpense(tx.getId());
                }

                CashPaymentMethod cashMethod = resolveCashPaymentMethod(payroll.getPaymentMethod(), null);
                String teacherName = teacher.getFirstName() + " " + teacher.getLastName();
                var cashTx = cashRegisterService.recordExpense(
                    payroll.getCashRegister().getId(),
                    finalNetSalary,
                    cashMethod,
                    "Oylik: " + teacherName,
                    "Oylik to'lovi (" + payroll.getMonth() + "/" + payroll.getYear() + ")",
                    payroll.getPaymentDate() != null ? payroll.getPaymentDate() : LocalDate.now(),
                    currentUser(),
                    null,
                    teacher,
                    null,
                    null);
                payroll.setCashRegister(cashTx.getCashRegister());
            }
        }
        return toResponse(payrollRepository.save(payroll));
    }

    @Transactional
    public void deletePayroll(Long id) {
        payrollRepository.delete(findById(id));
    }

    @Transactional
    public PayrollResponse markAsPaid(Long id, PayrollPayDto payDto) {
        Payroll payroll = findById(id);
        payroll.setStatus("PAID");
        String paymentMethod = payDto != null && payDto.getPaymentMethod() != null
                && !payDto.getPaymentMethod().isBlank()
            ? payDto.getPaymentMethod() : "CASH";
        payroll.setPaymentMethod(paymentMethod);
        payroll.setPaymentDate(LocalDate.now());

        Payroll saved = payrollRepository.save(payroll);

        if (payDto != null && payDto.getCashRegisterId() != null) {
            CashPaymentMethod cashMethod = resolveCashPaymentMethod(saved.getPaymentMethod(), payDto.getPaymentMethodForCash());
            Teacher teacher = saved.getTeacher();
            String name = teacher != null
                ? teacher.getFirstName() + " " + teacher.getLastName()
                : (saved.getUser() != null
                    ? saved.getUser().getFirstName() + " " + saved.getUser().getLastName() : "");
            var cashTx = cashRegisterService.recordExpense(
                payDto.getCashRegisterId(),
                saved.getNetSalary(),
                cashMethod,
                "Oylik: " + name,
                "Oylik to'lovi (" + saved.getMonth() + "/" + saved.getYear() + ")",
                LocalDate.now(),
                currentUser(),
                null,
                teacher,
                null,
                null);
            saved.setCashRegister(cashTx.getCashRegister());
            saved = payrollRepository.save(saved);
        }

        return toResponse(saved);
    }

    private User currentUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username).orElse(null);
    }

    private static CashPaymentMethod resolveCashPaymentMethod(String paymentMethod, String paymentMethodForCash) {
        if ("CASH_AND_CARD".equalsIgnoreCase(paymentMethod) || "CASH_AND_CARD".equalsIgnoreCase(paymentMethodForCash)) {
            return CashPaymentMethod.CASH_AND_CARD;
        }
        if (paymentMethodForCash != null) {
            try {
                return CashPaymentMethod.valueOf(paymentMethodForCash.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (paymentMethod != null) {
            try {
                return CashPaymentMethod.valueOf(paymentMethod.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
            }
            if ("BANK_TRANSFER".equalsIgnoreCase(paymentMethod.trim()) || "BANK".equalsIgnoreCase(paymentMethod.trim())) {
                return CashPaymentMethod.ONLINE;
            }
        }
        return CashPaymentMethod.CASH;
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
        p.setAllowances(req.getAllowances() != null ? req.getAllowances() : BigDecimal.ZERO);
        p.setDeductions(BigDecimal.ZERO);
        BigDecimal basic = req.getBasicSalary() != null ? req.getBasicSalary() : BigDecimal.ZERO;
        BigDecimal allowances = p.getAllowances();
        p.setNetSalary(basic.add(allowances));
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
        Long teacherId = p.getTeacher() != null ? p.getTeacher().getId() : null;
        String teacherName = null;
        if (p.getTeacher() != null) {
            teacherName = p.getTeacher().getFirstName() + " " + p.getTeacher().getLastName();
        } else if (p.getUser() != null) {
            teacherName = p.getUser().getFirstName() + " " + p.getUser().getLastName();
        }
        return PayrollResponse.builder()
            .id(p.getId()).uuid(p.getUuid())
            .teacherId(teacherId)
            .teacherName(teacherName)
            .userId(p.getUser() != null ? p.getUser().getId() : null)
            .userName(p.getUser() != null
                ? (p.getUser().getFirstName() + " " + p.getUser().getLastName()).trim() : null)
            .month(p.getMonth()).year(p.getYear())
            .basicSalary(p.getBasicSalary()).allowances(p.getAllowances())
            .deductions(p.getDeductions()).netSalary(p.getNetSalary())
            .bonusPenaltyAdjustment(p.getBonusPenaltyAdjustment())
            .paidStudentCount(p.getPaidStudentCount())
            .newStudentCount(p.getNewStudentCount())
            .kpiApplied(p.getKpiApplied())
            .kpiAmount(p.getKpiAmount())
            .calculationDetails(p.getCalculationDetails())
            .paymentDate(p.getPaymentDate()).paymentMethod(p.getPaymentMethod())
            .status(p.getStatus()).notes(p.getNotes())
            .createdByName(p.getCreatedBy() != null ? p.getCreatedBy().getUsername() : null)
            .cashRegisterId(p.getCashRegister() != null ? p.getCashRegister().getId() : null)
            .cashRegisterName(p.getCashRegister() != null ? p.getCashRegister().getName() : null)
            .createdAt(p.getCreatedAt()).build();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
