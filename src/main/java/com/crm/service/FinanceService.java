package com.crm.service;

import com.crm.dto.request.ExpenseRequest;
import com.crm.dto.response.ExpenseResponse;
import com.crm.dto.response.FinanceReportResponse;
import com.crm.entity.Expense;
import com.crm.entity.Teacher;
import com.crm.entity.User;
import com.crm.entity.enums.PaymentChannel;
import com.crm.entity.enums.PaymentMethod;
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

import com.crm.entity.enums.ExpenseCategory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FinanceService {

    private final ExpenseRepository expenseRepository;
    private final IncomeRepository incomeRepository;
    private final PaymentRepository paymentRepository;
    private final TeacherRepository teacherRepository;
    private final UserRepository userRepository;
    private final CashRegisterService cashRegisterService;
    private final PayrollRepository payrollRepository;
    private final com.crm.repository.CashTransactionRepository cashTransactionRepository;
    private final CashChannelService cashChannelService;

    @Transactional(readOnly = true)
    public List<ExpenseResponse> getExpenses(LocalDate from, LocalDate to) {
        LocalDate start = from != null ? from : LocalDate.now().withDayOfMonth(1);
        LocalDate end = to != null ? to : LocalDate.now();
        return expenseRepository.findByExpenseDateBetweenOrderByExpenseDateDesc(start, end)
            .stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Page<ExpenseResponse> getExpensesFiltered(
            LocalDate from, LocalDate to, String category, int page, int size) {

        ExpenseCategory cat = parseExpenseCategory(category);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "expenseDate"));

        final LocalDate fromDate = from;
        final LocalDate toDate = to;
        final ExpenseCategory categoryFilter = cat;

        Specification<Expense> spec = Specification.where(null);
        if (fromDate != null) {
            spec = spec.and((root, q, cb) ->
                cb.greaterThanOrEqualTo(root.get("expenseDate"), fromDate));
        }
        if (toDate != null) {
            spec = spec.and((root, q, cb) ->
                cb.lessThanOrEqualTo(root.get("expenseDate"), toDate));
        }
        if (categoryFilter != null) {
            spec = spec.and((root, q, cb) ->
                cb.equal(root.get("category"), categoryFilter));
        }

        return expenseRepository.findAll(spec, pageable).map(this::toResponse);
    }

    private static ExpenseCategory parseExpenseCategory(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        try {
            return ExpenseCategory.valueOf(category.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Transactional
    public ExpenseResponse createExpense(ExpenseRequest request) {
        Expense expense = Expense.builder()
            .category(request.getCategory())
            .title(request.getTitle())
            .amount(request.getAmount())
            .expenseDate(request.getExpenseDate())
            .description(request.getDescription())
            .notes(request.getNotes())
            .build();

        if (request.getTeacherId() != null) {
            Teacher teacher = teacherRepository.findById(request.getTeacherId())
                .orElseThrow(() -> new ResourceNotFoundException("Teacher", request.getTeacherId()));
            expense.setTeacher(teacher);
        }

        Expense saved = expenseRepository.save(expense);

        if (request.getCashRegisterId() != null) {
            PaymentMethod cashMethod = resolveCashPaymentMethod(request.getPaymentMethodForCash());
            User creator = currentUser();
            var cashTx = cashRegisterService.recordExpense(
                request.getCashRegisterId(),
                saved.getAmount(),
                cashMethod,
                "Xarajat: " + saved.getCategory(),
                saved.getDescription(),
                saved.getExpenseDate(),
                creator,
                null,
                null,
                null,
                null,
                request.getCashPart(),
                request.getCardPart());
            saved.setCashRegister(cashTx.getCashRegister());
            saved = expenseRepository.save(saved);
        }

        return toResponse(saved);
    }

    private User currentUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username).orElse(null);
    }

    /** Eski "PLASTIC" nomi ham qo'llab-quvvatlanadi (-> CARD). Ko'rsatilmasa — naqd. */
    private static PaymentMethod resolveCashPaymentMethod(String paymentMethodForCash) {
        return PaymentMethod.parseOrDefault(paymentMethodForCash, PaymentMethod.CASH);
    }

    @Transactional(readOnly = true)
    public FinanceReportResponse getFinanceReport(LocalDate from, LocalDate to) {
        LocalDate start = from != null ? from : LocalDate.now().withDayOfMonth(1);
        LocalDate end = to != null ? to : LocalDate.now();

        BigDecimal totalIncome = Optional.ofNullable(
            paymentRepository.sumCashAmountByDateRange(start, end)
        ).orElse(BigDecimal.ZERO);

        BigDecimal totalExpenses = Optional.ofNullable(
            expenseRepository.sumByDateRange(start, end)
        ).orElse(BigDecimal.ZERO);

        // Income by category
        Map<String, BigDecimal> incomeByCategory = new LinkedHashMap<>();
        incomeRepository.sumByCategory(start, end).forEach(row ->
            incomeByCategory.put(row[0].toString(), (BigDecimal) row[1]));

        // Expenses by category
        Map<String, BigDecimal> expenseByCategory = new LinkedHashMap<>();
        expenseRepository.sumByCategory(start, end).forEach(row ->
            expenseByCategory.put(row[0].toString(), (BigDecimal) row[1]));

        // Oylik (payroll-v2 §11 #8): PAID, paidAt ∈ [start, end]; CANCELLED kirmaydi.
        // totalExpenses faqat Expense jadvalidan — oylikning kassa chiqimi u yerda yo'q, ikki marta ayirilmaydi.
        Map<String, BigDecimal> payrollByRole = new LinkedHashMap<>();
        BigDecimal payrollPaid = BigDecimal.ZERO;
        for (Object[] row : payrollRepository.sumPaidByRole(start.atStartOfDay(), end.plusDays(1).atStartOfDay())) {
            String role = row[0] != null ? row[0].toString() : "OTHER";
            BigDecimal sum = row[1] != null ? (BigDecimal) row[1] : BigDecimal.ZERO;
            payrollByRole.merge(role, sum, BigDecimal::add);
            payrollPaid = payrollPaid.add(sum);
        }

        // Imtihon to'lovlari (leaves-exams-contracts §4.2): o'quvchi to'lovi emas — totalIncome ga kirmaydi,
        // lekin markaz daromadi: netProfit ga qo'shiladi. Kirim − bekor qilinganlarning REVERSAL i.
        BigDecimal examFees = Optional.ofNullable(cashTransactionRepository.sumExamFees(start, end))
            .orElse(BigDecimal.ZERO);

        // Kassa oqimi to'lov usuli guruhlari bo'yicha (hamma kassalar birga)
        Map<PaymentChannel, CashChannelService.Flow> flows = cashChannelService.flows(null, start, end);
        Map<String, BigDecimal> incomeByMethod = new LinkedHashMap<>();
        Map<String, BigDecimal> expenseByMethod = new LinkedHashMap<>();
        for (PaymentChannel c : PaymentChannel.values()) {
            incomeByMethod.put(c.name(), flows.get(c).netIncome());
            expenseByMethod.put(c.name(), flows.get(c).netExpense());
        }

        return FinanceReportResponse.builder()
            .totalIncome(totalIncome)
            .examFees(examFees)
            .totalExpenses(totalExpenses)
            .payrollPaid(payrollPaid)
            .payrollByRole(payrollByRole)
            .netProfit(totalIncome.add(examFees).subtract(totalExpenses).subtract(payrollPaid))
            .incomeByCategory(incomeByCategory)
            .expenseByCategory(expenseByCategory)
            .incomeByMethod(incomeByMethod)
            .expenseByMethod(expenseByMethod)
            .cashFlowByMethod(CashChannelService.toDtos(flows))
            .period(start + " to " + end)
            .build();
    }

    private ExpenseResponse toResponse(Expense e) {
        return ExpenseResponse.builder()
            .id(e.getId())
            .uuid(e.getUuid())
            .category(e.getCategory())
            .title(e.getTitle())
            .amount(e.getAmount())
            .expenseDate(e.getExpenseDate())
            .teacherName(e.getTeacher() != null
                ? e.getTeacher().getFirstName() + " " + e.getTeacher().getLastName() : null)
            .description(e.getDescription())
            .createdAt(e.getCreatedAt())
            .cashRegisterId(e.getCashRegister() != null ? e.getCashRegister().getId() : null)
            .cashRegisterName(e.getCashRegister() != null ? e.getCashRegister().getName() : null)
            .build();
    }
}
