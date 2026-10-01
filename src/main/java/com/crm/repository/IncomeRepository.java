package com.crm.repository;

import com.crm.entity.Income;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface IncomeRepository extends JpaRepository<Income, Long> {

    List<Income> findByIncomeDateBetweenOrderByIncomeDateDesc(LocalDate from, LocalDate to);

    /** Billing v2: bekor qilingan to'lov kirimi saqlanadi, lekin hisobotga kirmaydi (§6.4). */
    @Query("SELECT SUM(i.amount) FROM Income i LEFT JOIN i.payment p "
           + "WHERE i.incomeDate BETWEEN :from AND :to "
           + "AND (p IS NULL OR p.status = com.crm.entity.enums.PaymentStatus.PAID)")
    BigDecimal sumByDateRange(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT i.category, SUM(i.amount) FROM Income i LEFT JOIN i.payment p "
           + "WHERE i.incomeDate BETWEEN :from AND :to "
           + "AND (p IS NULL OR p.status = com.crm.entity.enums.PaymentStatus.PAID) "
           + "GROUP BY i.category")
    List<Object[]> sumByCategory(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
