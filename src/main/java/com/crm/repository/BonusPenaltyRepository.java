package com.crm.repository;

import com.crm.entity.BonusPenalty;
import com.crm.entity.enums.BonusPenaltyStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BonusPenaltyRepository extends JpaRepository<BonusPenalty, Long>,
        JpaSpecificationExecutor<BonusPenalty> {

    List<BonusPenalty> findByTeacherIdAndStatus(Long teacherId, BonusPenaltyStatus status);

    List<BonusPenalty> findByStudentIdAndStatus(Long studentId, BonusPenaltyStatus status);

    /** STAFF (ADMIN/SALES) bonuslari — payroll-v2 §11 #5. */
    List<BonusPenalty> findByUser_IdAndStatus(Long userId, BonusPenaltyStatus status);

    /** Bekor qilishda: shu to'lovda qo'llangan bonus/jarimalar. */
    List<BonusPenalty> findByAppliedToPaymentId(Long paymentId);

    /** Payroll bekor qilinganda: shu oylikka qo'llangan bonus/jarimalar (payroll-v2 §4). */
    List<BonusPenalty> findByAppliedToPayrollId(Long payrollId);

    List<BonusPenalty> findByTeacherIdOrderByEffectiveDateDesc(Long teacherId);
}
