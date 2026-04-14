package com.crm.repository;

import com.crm.entity.Expense;
import com.crm.entity.enums.ExpenseCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface ExpenseRepository extends JpaRepository<Expense, Long> {

    List<Expense> findByExpenseDateBetweenOrderByExpenseDateDesc(LocalDate from, LocalDate to);

    @Query("SELECT e FROM Expense e WHERE " +
           "(:from IS NULL OR e.expenseDate >= :from) AND " +
           "(:to IS NULL OR e.expenseDate <= :to) AND " +
           "(:category IS NULL OR e.category = :category)")
    Page<Expense> findFiltered(
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("category") ExpenseCategory category,
            Pageable pageable);

    List<Expense> findByCategory(ExpenseCategory category);

    @Query("SELECT SUM(e.amount) FROM Expense e WHERE e.expenseDate BETWEEN :from AND :to")
    BigDecimal sumByDateRange(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT e.category, SUM(e.amount) FROM Expense e " +
           "WHERE e.expenseDate BETWEEN :from AND :to GROUP BY e.category")
    List<Object[]> sumByCategory(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT EXTRACT(MONTH FROM e.expenseDate), EXTRACT(YEAR FROM e.expenseDate), SUM(e.amount) FROM Expense e " +
           "WHERE e.expenseDate >= :from " +
           "GROUP BY EXTRACT(YEAR FROM e.expenseDate), EXTRACT(MONTH FROM e.expenseDate) " +
           "ORDER BY EXTRACT(YEAR FROM e.expenseDate), EXTRACT(MONTH FROM e.expenseDate)")
    List<Object[]> getMonthlyExpenses(@Param("from") LocalDate from);
}
