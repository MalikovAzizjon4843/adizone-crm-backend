package com.crm.service;

import com.crm.dto.request.ExpenseRequest;
import com.crm.dto.response.ExpenseResponse;
import com.crm.dto.response.FinanceReportResponse;
import com.crm.entity.Expense;
import com.crm.entity.Teacher;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Transactional(readOnly = true)
    public List<ExpenseResponse> getExpenses(LocalDate from, LocalDate to) {
        LocalDate start = from != null ? from : LocalDate.now().withDayOfMonth(1);
        LocalDate end = to != null ? to : LocalDate.now();
        return expenseRepository.findByExpenseDateBetweenOrderByExpenseDateDesc(start, end)
            .stream().map(this::toResponse).collect(Collectors.toList());
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

        return toResponse(expenseRepository.save(expense));
    }

    @Transactional(readOnly = true)
    public FinanceReportResponse getFinanceReport(LocalDate from, LocalDate to) {
        LocalDate start = from != null ? from : LocalDate.now().withDayOfMonth(1);
        LocalDate end = to != null ? to : LocalDate.now();

        BigDecimal totalIncome = Optional.ofNullable(
            paymentRepository.sumAmountByDateRange(start, end)
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

        return FinanceReportResponse.builder()
            .totalIncome(totalIncome)
            .totalExpenses(totalExpenses)
            .netProfit(totalIncome.subtract(totalExpenses))
            .incomeByCategory(incomeByCategory)
            .expenseByCategory(expenseByCategory)
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
            .build();
    }
}
