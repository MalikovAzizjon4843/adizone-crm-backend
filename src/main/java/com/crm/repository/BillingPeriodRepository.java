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
}
