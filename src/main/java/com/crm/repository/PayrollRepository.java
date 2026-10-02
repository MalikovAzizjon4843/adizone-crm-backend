package com.crm.repository;

import com.crm.entity.Payroll;
import com.crm.entity.enums.PayrollStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface PayrollRepository extends JpaRepository<Payroll, Long>, JpaSpecificationExecutor<Payroll> {

    List<Payroll> findByTeacherIdOrderByYearDescMonthDesc(Long teacherId);

    /** Bitta xodim + oy uchun FAOL (CANCELLED emas) payroll — ko'pi bilan bitta (payroll-v2 §1). */
    @Query("""
        SELECT p FROM Payroll p
        WHERE p.user.id = :userId AND p.month = :month AND p.year = :year
          AND p.status <> com.crm.entity.enums.PayrollStatus.CANCELLED
        """)
    Optional<Payroll> findActive(
        @Param("userId") Long userId, @Param("month") Integer month, @Param("year") Integer year);

    @Query("""
        SELECT p FROM Payroll p
        WHERE p.teacher.id = :teacherId AND p.month = :month AND p.year = :year
          AND p.status <> com.crm.entity.enums.PayrollStatus.CANCELLED
        """)
    Optional<Payroll> findActiveByTeacher(
        @Param("teacherId") Long teacherId, @Param("month") Integer month, @Param("year") Integer year);

    /** To'lanmagan (DRAFT + APPROVED) — analitika kartochkasi. */
    long countByStatusIn(Collection<PayrollStatus> statuses);

    /** Qoida tasdiqlangan/to'langan oylikda ishlatilganmi (§11 #3 — tahrir 409). */
    boolean existsBySalaryRuleIdAndStatusIn(Long salaryRuleId, Collection<PayrollStatus> statuses);

    /** Moliya hisoboti (§11 #8): rol bo'yicha Σ netSalary, PAID, paidAt ∈ [a, b). */
    @Query("""
        SELECT u.role, COALESCE(SUM(p.netSalary), 0) FROM Payroll p LEFT JOIN p.user u
        WHERE p.status = com.crm.entity.enums.PayrollStatus.PAID AND p.paidAt >= :a AND p.paidAt < :b
        GROUP BY u.role
        """)
    List<Object[]> sumPaidByRole(@Param("a") LocalDateTime a, @Param("b") LocalDateTime b);
}
