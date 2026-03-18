package com.crm.repository;

import com.crm.entity.Expense;
import com.crm.entity.enums.ExpenseCategory;
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

    List<Expense> findByCategory(ExpenseCategory category);

    @Query("SELECT SUM(e.amount) FROM Expense e WHERE e.expenseDate BETWEEN :from AND :to")
    BigDecimal sumByDateRange(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT e.category, SUM(e.amount) FROM Expense e " +
           "WHERE e.expenseDate BETWEEN :from AND :to GROUP BY e.category")
    List<Object[]> sumByCategory(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT MONTH(e.expenseDate), YEAR(e.expenseDate), SUM(e.amount) FROM Expense e " +
           "WHERE e.expenseDate >= :from " +
           "GROUP BY YEAR(e.expenseDate), MONTH(e.expenseDate) " +
           "ORDER BY YEAR(e.expenseDate), MONTH(e.expenseDate)")
    List<Object[]> getMonthlyExpenses(@Param("from") LocalDate from);
}
