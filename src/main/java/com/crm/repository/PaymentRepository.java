package com.crm.repository;

import com.crm.entity.Payment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByStudentIdOrderByPaymentDateDesc(Long studentId);

    Page<Payment> findByStudentId(Long studentId, Pageable pageable);

    @Query("SELECT SUM(p.amount) FROM Payment p WHERE p.paymentDate BETWEEN :from AND :to " +
           "AND p.status = 'PAID'")
    BigDecimal sumAmountByDateRange(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT EXTRACT(MONTH FROM p.paymentDate), EXTRACT(YEAR FROM p.paymentDate), SUM(p.amount) FROM Payment p " +
           "WHERE p.status = 'PAID' AND p.paymentDate >= :from " +
           "GROUP BY EXTRACT(YEAR FROM p.paymentDate), EXTRACT(MONTH FROM p.paymentDate) " +
           "ORDER BY EXTRACT(YEAR FROM p.paymentDate), EXTRACT(MONTH FROM p.paymentDate)")
    List<Object[]> getMonthlyRevenue(@Param("from") LocalDateTime from);

    @Query("SELECT p FROM Payment p WHERE p.paymentDate BETWEEN :from AND :to ORDER BY p.paymentDate DESC")
    List<Payment> findByDateRange(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(p) FROM Payment p WHERE p.paymentDate BETWEEN :from AND :to AND p.status = 'PAID'")
    long countPaidByDateRange(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    List<Payment> findTop10ByOrderByPaymentDateDesc();
}
