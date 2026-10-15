package com.crm.repository;

import com.crm.entity.Expense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface ExpenseRepository extends JpaRepository<Expense, Long>, JpaSpecificationExecutor<Expense> {

    List<Expense> findByExpenseDateBetweenOrderByExpenseDateDesc(LocalDate from, LocalDate to);

    List<Expense> findByCategory(String category);

    /** Kassasiz xarajatlar (bank orqali, faqat SA) — kassa yozuvi yo'q, moliya hisobotiga shu yerdan (V86). */
    @Query(value = "SELECT e.category, SUM(e.amount) FROM Expense e "
        + "WHERE e.cashRegister IS NULL AND e.expenseDate BETWEEN :from AND :to GROUP BY e.category")
    List<Object[]> sumByCategoryWithoutRegister(@Param(value = "from") LocalDate from, @Param(value = "to") LocalDate to);

    @Query(value = "SELECT e.expenseDate, SUM(e.amount) FROM Expense e "
        + "WHERE e.cashRegister IS NULL AND e.expenseDate BETWEEN :from AND :to GROUP BY e.expenseDate")
    List<Object[]> sumByDateWithoutRegister(@Param(value = "from") LocalDate from, @Param(value = "to") LocalDate to);

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
