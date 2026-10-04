package com.crm.repository;

import com.crm.entity.BillingPeriod;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface BillingPeriodRepository extends JpaRepository<BillingPeriod, Long> {

    List<BillingPeriod> findByStudentGroupIdOrderByPeriodStartAsc(Long studentGroupId);

    /** Batch: [studentGroupId, periodStart, periodEnd] — kutilayotganlar ro'yxati uchun (N+1 yo'q). */
    @Query("SELECT p.studentGroupId, p.periodStart, p.periodEnd FROM BillingPeriod p WHERE p.studentGroupId IN :ids")
    List<Object[]> findSpansByStudentGroupIds(@Param("ids") java.util.Collection<Long> ids);

    Optional<BillingPeriod> findByStudentGroupIdAndPeriodStart(Long studentGroupId, LocalDate periodStart);

    boolean existsByStudentGroupIdAndPeriodStart(Long studentGroupId, LocalDate periodStart);

    /** Eng oxirgi (eng katta period_start) davr. */
    Optional<BillingPeriod> findFirstByStudentGroupIdOrderByPeriodStartDesc(Long studentGroupId);

    /** {@code date} ni o'z ichiga olgan davr. */
    @Query("""
        SELECT p FROM BillingPeriod p
        WHERE p.studentGroupId = :sgId AND p.periodStart <= :date AND p.periodEnd >= :date
        """)
    Optional<BillingPeriod> findCovering(@Param("sgId") Long sgId, @Param("date") LocalDate date);

    long countByStudentGroupId(Long studentGroupId);

    List<BillingPeriod> findByMigrationRunIdAndStudentGroupId(Long migrationRunId, Long studentGroupId);

    @Modifying
    @Query("DELETE FROM BillingPeriod p WHERE p.migrationRunId = :runId AND p.studentGroupId = :sgId")
    int deleteByMigrationRunAndSg(@Param("runId") Long runId, @Param("sgId") Long sgId);

    /**
     * O'qituvchi KPI (to'lov / o'z vaqtida to'lov): muddati {@code [from, to]} da kelgan yozilgan
     * davrlar. Qator: [teacherId (davr yozilgandagi, bo'lmasa guruhning hozirgi), dueDate, graceUntil,
     * paidOn, amount, refundedAmount]. Qaytarilgan / {@code billing_hold} ni chaqiruvchi tashlaydi
     * ({@code CollectionsMetricsService} bilan bir xil ta'rif).
     */
    @Query("""
        SELECT COALESCE(bp.teacherId, t.id), COALESCE(bp.dueDate, bp.periodStart), bp.graceUntil, bp.paidOn,
               bp.amount, bp.refundedAmount
        FROM BillingPeriod bp
        JOIN StudentGroup sg ON sg.id = bp.studentGroupId
        LEFT JOIN sg.group g
        LEFT JOIN g.teacher t
        WHERE COALESCE(bp.dueDate, bp.periodStart) BETWEEN :from AND :to
          AND bp.status IN :statuses
          AND bp.chargeTxId IS NOT NULL
          AND (sg.billingHold IS NULL OR sg.billingHold = false)
        """)
    List<Object[]> findDueForTeacherKpi(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                        @Param("statuses") java.util.Collection<com.crm.entity.enums.BillingPeriodStatus> statuses);
}
