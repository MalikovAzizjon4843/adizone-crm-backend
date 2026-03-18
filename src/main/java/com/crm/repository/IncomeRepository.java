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

    @Query("SELECT SUM(i.amount) FROM Income i WHERE i.incomeDate BETWEEN :from AND :to")
    BigDecimal sumByDateRange(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT i.category, SUM(i.amount) FROM Income i " +
           "WHERE i.incomeDate BETWEEN :from AND :to GROUP BY i.category")
    List<Object[]> sumByCategory(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
