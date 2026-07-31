package com.crm.repository;

import com.crm.entity.Payment;
import com.crm.entity.enums.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long>, JpaSpecificationExecutor<Payment> {

    List<Payment> findByStudentIdOrderByPaymentDateDesc(Long studentId);

    List<Payment> findByStudent_IdOrderByCreatedAtDesc(Long studentId);

    Page<Payment> findByStudentId(Long studentId, Pageable pageable);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p WHERE p.paymentDate BETWEEN :from AND :to " +
           "AND p.status = 'PAID'")
    BigDecimal sumAmountByDateRange(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT EXTRACT(MONTH FROM p.paymentDate), EXTRACT(YEAR FROM p.paymentDate), SUM(p.amount) FROM Payment p " +
           "WHERE p.status = 'PAID' AND p.paymentDate >= :from " +
           "GROUP BY EXTRACT(YEAR FROM p.paymentDate), EXTRACT(MONTH FROM p.paymentDate) " +
           "ORDER BY EXTRACT(YEAR FROM p.paymentDate), EXTRACT(MONTH FROM p.paymentDate)")
    List<Object[]> getMonthlyRevenue(@Param("from") LocalDate from);

    @Query(value = """
        SELECT CAST(p.payment_date AS date) AS bucket_date, COALESCE(SUM(p.amount), 0) AS amount
        FROM payments p
        WHERE p.status = 'PAID'
          AND p.payment_date BETWEEN :from AND :to
        GROUP BY CAST(p.payment_date AS date)
        ORDER BY CAST(p.payment_date AS date)
        """, nativeQuery = true)
    List<Object[]> getDailyRevenueBuckets(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query(value = """
        SELECT CAST(DATE_TRUNC('month', p.payment_date) AS date) AS bucket_date,
               COALESCE(SUM(p.amount), 0) AS amount
        FROM payments p
        WHERE p.status = 'PAID'
          AND p.payment_date BETWEEN :from AND :to
        GROUP BY DATE_TRUNC('month', p.payment_date)
        ORDER BY DATE_TRUNC('month', p.payment_date)
        """, nativeQuery = true)
    List<Object[]> getMonthlyRevenueBuckets(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query(value = """
        SELECT CAST(DATE_TRUNC('year', p.payment_date) AS date) AS bucket_date,
               COALESCE(SUM(p.amount), 0) AS amount
        FROM payments p
        WHERE p.status = 'PAID'
          AND p.payment_date BETWEEN :from AND :to
        GROUP BY DATE_TRUNC('year', p.payment_date)
        ORDER BY DATE_TRUNC('year', p.payment_date)
        """, nativeQuery = true)
    List<Object[]> getYearlyRevenueBuckets(@Param("from") LocalDate from, @Param("to") LocalDate to);

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

    Optional<Payment> findFirstByStudent_IdAndPeriodEndIsNotNullOrderByPeriodEndDesc(Long studentId);

    Optional<Payment> findFirstByStudentGroup_IdAndPeriodEndIsNotNullOrderByPeriodEndDesc(Long studentGroupId);

    Optional<Payment> findFirstByStudentGroup_IdOrderByPaymentDateDesc(Long studentGroupId);

    Optional<Payment> findFirstByStudent_IdOrderByPaymentDateDesc(Long studentId);

    @Query("SELECT p FROM Payment p WHERE p.periodStart IS NULL OR p.periodEnd IS NULL")
    List<Payment> findWithMissingPeriods();

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

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p " +
           "WHERE p.student.id = :studentId " +
           "AND p.group.id = :groupId " +
           "AND p.status = 'PAID'")
    BigDecimal sumPaidByStudentAndGroup(
        @Param("studentId") Long studentId,
        @Param("groupId") Long groupId);

    @Query("""
        SELECT COALESCE(SUM(COALESCE(p.amount, 0) + COALESCE(p.balanceUsed, 0)), 0)
        FROM Payment p
        WHERE p.studentGroup.id = :studentGroupId
          AND p.status = 'PAID'
        """)
    BigDecimal sumCreditsByStudentGroupId(@Param("studentGroupId") Long studentGroupId);

    @Query("""
        SELECT COALESCE(SUM(COALESCE(p.amount, 0) + COALESCE(p.balanceUsed, 0)), 0)
        FROM Payment p
        WHERE p.student.id = :studentId
          AND p.group.id = :groupId
          AND p.status = 'PAID'
        """)
    BigDecimal sumCreditsByStudentAndGroup(
        @Param("studentId") Long studentId,
        @Param("groupId") Long groupId);

    /** Batch: userId, paymentCount, paymentSum */
    @Query("""
        SELECT p.receivedBy.id, COUNT(p), COALESCE(SUM(p.amount), 0)
        FROM Payment p
        WHERE p.receivedBy IS NOT NULL
          AND p.status = 'PAID'
          AND p.paymentDate BETWEEN :from AND :to
        GROUP BY p.receivedBy.id
        """)
    List<Object[]> sumReceivedGroupedByUser(
        @Param("from") LocalDate from,
        @Param("to") LocalDate to);

    @Query("""
        SELECT COUNT(p), COALESCE(SUM(p.amount), 0)
        FROM Payment p
        WHERE p.receivedBy.id = :userId
          AND p.status = 'PAID'
          AND p.paymentDate BETWEEN :from AND :to
        """)
    List<Object[]> sumReceivedByUserInRange(
        @Param("userId") Long userId,
        @Param("from") LocalDate from,
        @Param("to") LocalDate to);

    @Query("""
        SELECT COUNT(DISTINCT p.student.id) FROM Payment p
        WHERE p.status = 'PAID'
          AND p.paymentDate >= :monthStart
          AND p.student.id NOT IN (
              SELECT p2.student.id FROM Payment p2
              WHERE p2.status = 'PAID' AND p2.paymentDate < :monthStart
          )
        """)
    long countFirstPaymentsThisMonth(@Param("monthStart") LocalDate monthStart);

    /**
     * TEACHER: DISTINCT student+group juftliklari (oyda to'lagan).
     * [studentId, studentName, groupId, groupName, paymentDate]
     */
    @Query(value = """
        SELECT DISTINCT ON (p.student_id, COALESCE(p.group_id, sg.group_id))
               p.student_id,
               CONCAT(s.first_name, ' ', s.last_name),
               COALESCE(p.group_id, sg.group_id),
               g.group_name,
               p.payment_date
        FROM payments p
        JOIN students s ON s.id = p.student_id
        LEFT JOIN student_groups sg ON sg.id = p.student_group_id
        JOIN groups g ON g.id = COALESCE(p.group_id, sg.group_id)
        WHERE g.teacher_id = :teacherId
          AND p.status = 'PAID'
          AND p.payment_date BETWEEN :from AND :to
        ORDER BY p.student_id, COALESCE(p.group_id, sg.group_id), p.payment_date
        """, nativeQuery = true)
    List<Object[]> findPaidStudentGroupPairsForTeacher(
        @Param("teacherId") Long teacherId,
        @Param("from") LocalDate from,
        @Param("to") LocalDate to);

    /**
     * ADMIN/SALES: birinchi to'lov shu oyda bo'lgan attributed students.
     * [studentId, studentName, firstPaymentDate]
     */
    @Query(value = """
        SELECT s.id,
               CONCAT(s.first_name, ' ', s.last_name),
               fp.first_payment_date
        FROM students s
        JOIN (
            SELECT student_id, MIN(id) AS first_payment_id, MIN(payment_date) AS first_payment_date
            FROM payments
            WHERE status = 'PAID'
            GROUP BY student_id
        ) fp ON fp.student_id = s.id
        JOIN payments p ON p.id = fp.first_payment_id
        WHERE s.attributed_user_id = :userId
          AND p.payment_date BETWEEN :from AND :to
        ORDER BY p.payment_date, s.id
        """, nativeQuery = true)
    List<Object[]> findNewStudentsByAttributedUser(
        @Param("userId") Long userId,
        @Param("from") LocalDate from,
        @Param("to") LocalDate to);
}
