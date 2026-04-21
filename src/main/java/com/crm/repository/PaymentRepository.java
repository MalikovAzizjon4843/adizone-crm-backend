package com.crm.repository;

import com.crm.entity.Payment;
import com.crm.entity.enums.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByStudentIdOrderByPaymentDateDesc(Long studentId);

    Page<Payment> findByStudentId(Long studentId, Pageable pageable);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p WHERE p.paymentDate BETWEEN :from AND :to " +
           "AND p.status = 'PAID'")
    BigDecimal sumAmountByDateRange(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT EXTRACT(MONTH FROM p.paymentDate), EXTRACT(YEAR FROM p.paymentDate), SUM(p.amount) FROM Payment p " +
           "WHERE p.status = 'PAID' AND p.paymentDate >= :from " +
           "GROUP BY EXTRACT(YEAR FROM p.paymentDate), EXTRACT(MONTH FROM p.paymentDate) " +
           "ORDER BY EXTRACT(YEAR FROM p.paymentDate), EXTRACT(MONTH FROM p.paymentDate)")
    List<Object[]> getMonthlyRevenue(@Param("from") LocalDate from);

    @Query("SELECT p FROM Payment p WHERE p.paymentDate BETWEEN :from AND :to ORDER BY p.paymentDate DESC")
    List<Payment> findByDateRange(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT COUNT(p) FROM Payment p WHERE p.paymentDate BETWEEN :from AND :to AND p.status = 'PAID'")
    long countPaidByDateRange(@Param("from") LocalDate from, @Param("to") LocalDate to);

    List<Payment> findTop10ByOrderByPaymentDateDesc();

    List<Payment> findAllByOrderByPaymentDateDesc();

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p WHERE p.status = :status")
    BigDecimal sumAmountByStatus(@Param("status") PaymentStatus status);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p WHERE p.status = 'PAID' " +
           "AND p.paymentDate BETWEEN :from AND :to")
    BigDecimal sumPaidBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT COUNT(p) FROM Payment p WHERE p.student.id = :studentId AND p.group.id = :groupId " +
           "AND p.paymentDate >= :start AND p.paymentDate <= :end AND p.status = 'PAID'")
    long countPaidForStudentGroupInPeriod(
            @Param("studentId") Long studentId,
            @Param("groupId") Long groupId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end);

    @Query("SELECT p FROM Payment p WHERE " +
           "(:studentId IS NULL OR p.student.id = :studentId) AND " +
           "(:groupId IS NULL OR (p.group IS NOT NULL AND p.group.id = :groupId)) AND " +
           "(:status IS NULL OR p.status = :status) AND " +
           "(:from IS NULL OR p.paymentDate >= :from) AND " +
           "(:to IS NULL OR p.paymentDate <= :to)")
    Page<Payment> searchPayments(
            @Param("studentId") Long studentId,
            @Param("groupId") Long groupId,
            @Param("status") PaymentStatus status,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            Pageable pageable);

    Optional<Payment> findFirstByStudent_IdAndPeriodToIsNotNullOrderByPeriodToDesc(Long studentId);

    Page<Payment> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("SELECT p FROM Payment p ORDER BY p.createdAt DESC")
    Page<Payment> findAllOrderByCreatedAtDesc(Pageable pageable);

    @Query("SELECT p FROM Payment p " +
           "WHERE (:studentId IS NULL OR p.student.id = :studentId) " +
           "AND (:groupId IS NULL OR p.group.id = :groupId) " +
           "AND (:status IS NULL OR p.status = :status) " +
           "AND (:from IS NULL OR p.paymentDate >= :from) " +
           "AND (:to IS NULL OR p.paymentDate <= :to) " +
           "ORDER BY p.createdAt DESC")
    Page<Payment> findFiltered(
        @Param("studentId") Long studentId,
        @Param("groupId") Long groupId,
        @Param("status") String status,
        @Param("from") LocalDate from,
        @Param("to") LocalDate to,
        Pageable pageable);
}
