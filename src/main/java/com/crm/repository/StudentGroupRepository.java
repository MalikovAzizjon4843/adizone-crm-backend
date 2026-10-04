package com.crm.repository;

import com.crm.entity.StudentGroup;
import com.crm.entity.enums.PaymentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface StudentGroupRepository extends JpaRepository<StudentGroup, Long>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<StudentGroup> {

    List<StudentGroup> findByStudentId(Long studentId);

    /**
     * Kutilayotgan to'lovlar (R2, billing-v2 §14.2): hisoblanadigan bo'lishi mumkin bo'lgan yozilmalar — faol,
     * muzlatilmagan, sinovda emas, guruh ACTIVE/FORMING, o'quvchi ACTIVE. Aniq shart — {@code BillingStatusService.isBillingOpen}.
     */
    @Query("""
        SELECT sg FROM StudentGroup sg
        JOIN FETCH sg.student s
        JOIN FETCH sg.group g
        LEFT JOIN FETCH g.course
        WHERE sg.isActive = true AND sg.frozenFrom IS NULL
          AND (sg.isTrial = false OR sg.isTrial IS NULL)
          AND g.status IN (com.crm.entity.enums.GroupStatus.ACTIVE, com.crm.entity.enums.GroupStatus.FORMING)
          AND s.status = com.crm.entity.enums.StudentStatus.ACTIVE
        """)
    List<StudentGroup> findExpectedCandidates();

    /** Ro'yxatlar uchun batch: o'quvchilarning barcha yozilmalari (guruh bilan). */
    @Query("SELECT sg FROM StudentGroup sg JOIN FETCH sg.group WHERE sg.student.id IN :studentIds")
    List<StudentGroup> findWithGroupByStudentIds(@Param("studentIds") Collection<Long> studentIds);

    /**
     * Muzlatilgan yozilmalar — {@code EnrollmentLifecycleService.isFrozen} bilan bir xil shart:
     * {@code frozenFrom} bor, yoki eski (v1) muzlatish — nofaol va {@code exitReason = 'FROZEN'}.
     */
    @Query("""
        SELECT sg FROM StudentGroup sg JOIN FETCH sg.student JOIN FETCH sg.group
        WHERE sg.frozenFrom IS NOT NULL
           OR ((sg.isActive = false OR sg.isActive IS NULL) AND sg.exitReason = 'FROZEN')
        """)
    List<StudentGroup> findFrozenWithStudentAndGroup();

    List<StudentGroup> findByStudentIdOrderByJoinDateDesc(Long studentId);

    List<StudentGroup> findByGroupId(Long groupId);

    @Query("SELECT sg FROM StudentGroup sg WHERE sg.student.id = :studentId "
           + "AND sg.isActive = true AND sg.leaveDate IS NULL")
    List<StudentGroup> findActiveByStudentId(@Param("studentId") Long studentId);

    @Query("SELECT sg FROM StudentGroup sg WHERE sg.group.id = :groupId "
           + "AND sg.isActive = true AND sg.leaveDate IS NULL")
    List<StudentGroup> findByGroupIdAndIsActiveTrue(@Param("groupId") Long groupId);

    /** Canonical active enrollments for a group (alias). */
    @Query("SELECT sg FROM StudentGroup sg WHERE sg.group.id = :groupId "
           + "AND sg.isActive = true AND sg.leaveDate IS NULL")
    List<StudentGroup> findActiveByGroupId(@Param("groupId") Long groupId);

    @Query("SELECT sg FROM StudentGroup sg WHERE sg.group.id = :groupId "
           + "AND sg.isActive = true AND sg.leaveDate IS NULL")
    List<StudentGroup> findByGroup_IdAndIsActiveTrue(@Param("groupId") Long groupId);

    @Query("SELECT COUNT(sg) FROM StudentGroup sg WHERE sg.group.id = :groupId "
           + "AND sg.isActive = true AND sg.leaveDate IS NULL")
    long countByGroupIdAndIsActiveTrue(@Param("groupId") Long groupId);

    /** @deprecated use {@link #findActiveByStudentId(Long)} */
    @Query("SELECT sg FROM StudentGroup sg WHERE sg.student.id = :studentId "
           + "AND sg.isActive = true AND sg.leaveDate IS NULL")
    List<StudentGroup> findByStudentIdAndIsActiveTrue(@Param("studentId") Long studentId);

    @Query("SELECT sg FROM StudentGroup sg WHERE sg.student.id = :studentId AND sg.group.id = :groupId "
           + "AND sg.isActive = true AND sg.leaveDate IS NULL")
    Optional<StudentGroup> findByStudentIdAndGroupIdAndIsActiveTrue(
            @Param("studentId") Long studentId, @Param("groupId") Long groupId);

    @Query("SELECT CASE WHEN COUNT(sg) > 0 THEN true ELSE false END FROM StudentGroup sg "
           + "WHERE sg.student.id = :studentId AND sg.group.id = :groupId "
           + "AND sg.isActive = true AND sg.leaveDate IS NULL")
    boolean existsByStudentIdAndGroupIdAndIsActiveTrue(
            @Param("studentId") Long studentId, @Param("groupId") Long groupId);

    @Query("""
        SELECT sg FROM StudentGroup sg
        JOIN FETCH sg.student st
        JOIN FETCH sg.group g
        LEFT JOIN FETCH g.course
        WHERE sg.isActive = true AND sg.leaveDate IS NULL AND st.id IN :studentIds
        ORDER BY sg.joinDate DESC
        """)
    List<StudentGroup> findActiveByStudentIds(@Param("studentIds") Collection<Long> studentIds);

    @Query("SELECT sg FROM StudentGroup sg WHERE sg.isActive = true AND sg.leaveDate IS NULL "
           + "AND sg.student.status = 'ACTIVE'")
    List<StudentGroup> findAllActiveEnrollments();

    @Query("SELECT COUNT(sg) FROM StudentGroup sg WHERE sg.isActive = true AND sg.leaveDate IS NULL")
    long countActiveEnrollments();

    /** Guruhning qarzli (balance &lt; 0) SG lari — OVERDUE/PENDING ajratish BillingStatusService da. */
    @Query("""
        SELECT sg FROM StudentGroup sg JOIN FETCH sg.student
        WHERE sg.group.id = :groupId AND sg.balance < 0 AND sg.debtSince IS NOT NULL
        """)
    List<StudentGroup> findWithDebtByGroupId(@Param("groupId") Long groupId);

    @Query("SELECT COUNT(sg) FROM StudentGroup sg WHERE sg.group.id = :groupId "
           + "AND sg.isActive = true AND sg.leaveDate IS NULL")
    long countByGroup_IdAndIsActiveTrue(@Param("groupId") Long groupId);

    /** Barcha guruhlar uchun active o'quvchi soni (N+1 oldini olish). */
    @Query("""
        SELECT sg.group.id, COUNT(sg)
        FROM StudentGroup sg
        WHERE sg.isActive = true AND sg.leaveDate IS NULL
        GROUP BY sg.group.id
        """)
    List<Object[]> countActiveStudentsGroupedByGroupId();

    @Query("SELECT COUNT(sg) FROM StudentGroup sg WHERE sg.group.id IN :groupIds "
           + "AND sg.joinDate BETWEEN :from AND :to")
    long countByGroupIdsAndJoinDateBetween(@Param("groupIds") List<Long> groupIds,
                                            @Param("from") LocalDate from,
                                            @Param("to") LocalDate to);

    @Query("SELECT COUNT(sg) FROM StudentGroup sg WHERE sg.group.id IN :groupIds "
           + "AND sg.joinDate BETWEEN :from AND :to "
           + "AND sg.paymentStatus = com.crm.entity.enums.PaymentStatus.PAID")
    long countPaidByGroupIdsAndJoinDateBetween(@Param("groupIds") List<Long> groupIds,
                                                @Param("from") LocalDate from,
                                                @Param("to") LocalDate to);

    /** Diagnostika: isActive=true lekin leaveDate to'ldirilgan (nomuvofiq). */
    @Query("SELECT COUNT(sg) FROM StudentGroup sg WHERE sg.isActive = true AND sg.leaveDate IS NOT NULL")
    long countActiveFlagButHasLeaveDate();

    /** Diagnostika: leaveDate null lekin isActive=false (nomuvofiq). */
    @Query("SELECT COUNT(sg) FROM StudentGroup sg WHERE sg.isActive = false AND sg.leaveDate IS NULL")
    long countInactiveFlagButNoLeaveDate();

    /**
     * Batch active enrollments by teacher:
     * teacherId, activeCount, paidCount, billableCount (not TRIAL),
     * debtorCount (OVERDUE or nextPaymentDate < today, not TRIAL)
     */
    /**
     * Billing v2 (§4.5): debtor — SG darajasida OVERDUE, ya'ni
     * {@code balance < 0 AND debt_since < :overdueBefore} ({@code BillingStatusService.overdueBefore}).
     * paid — snapshot holati PAID; billable — sinovda emas.
     */
    @Query("""
        SELECT g.teacher.id,
               SUM(CASE WHEN sg.isActive = true THEN 1 ELSE 0 END),
               SUM(CASE WHEN sg.isActive = true
                         AND sg.paymentStatus = com.crm.entity.enums.PaymentStatus.PAID THEN 1 ELSE 0 END),
               SUM(CASE WHEN sg.isActive = true AND (sg.isTrial = false OR sg.isTrial IS NULL)
                        THEN 1 ELSE 0 END),
               SUM(CASE WHEN sg.balance < 0 AND sg.debtSince IS NOT NULL AND sg.debtSince < :overdueBefore
                        THEN 1 ELSE 0 END)
        FROM StudentGroup sg
        JOIN sg.group g
        WHERE g.teacher IS NOT NULL
          AND ((sg.isActive = true AND sg.leaveDate IS NULL) OR sg.frozenFrom IS NOT NULL)
        GROUP BY g.teacher.id
        """)
    List<Object[]> countActivePaymentStatsGroupedByTeacher(@Param("overdueBefore") LocalDate overdueBefore);

    /**
     * O'qituvchi KPI (saqlab qolish) — {@code [from, to]} oralig'i, guruhning hozirgi o'qituvchisi
     * bo'yicha, sinov yozilmalarisiz: [teacherId, oxirida ochiq, bitirgan, ketgan (churn)].
     * Churn — {@code ExitReasonCode.isChurn()}: TRANSFERRED / FROZEN / GRADUATED emas; kodi yo'q eski
     * qator {@code exit_reason} matni bo'yicha. Muzlatilgan yozilma ochiq ham, ketgan ham emas.
     */
    @Query("""
        SELECT g.teacher.id,
               SUM(CASE WHEN sg.joinDate <= :to
                         AND ((sg.leaveDate IS NULL AND sg.isActive = true) OR sg.leaveDate > :to)
                        THEN 1 ELSE 0 END),
               SUM(CASE WHEN sg.leaveDate BETWEEN :from AND :to
                         AND (sg.exitReasonCode = com.crm.entity.enums.ExitReasonCode.GRADUATED
                              OR (sg.exitReasonCode IS NULL AND sg.exitReason = 'GRADUATED'))
                        THEN 1 ELSE 0 END),
               SUM(CASE WHEN sg.leaveDate BETWEEN :from AND :to
                         AND (sg.isActive = false OR sg.isActive IS NULL)
                         AND ((sg.exitReasonCode IS NOT NULL
                               AND sg.exitReasonCode NOT IN (com.crm.entity.enums.ExitReasonCode.TRANSFERRED,
                                                             com.crm.entity.enums.ExitReasonCode.FROZEN,
                                                             com.crm.entity.enums.ExitReasonCode.GRADUATED))
                              OR (sg.exitReasonCode IS NULL AND sg.frozenFrom IS NULL
                                  AND (sg.exitReason IS NULL
                                       OR sg.exitReason NOT IN ('GRADUATED', 'TRANSFERRED', 'FROZEN'))))
                        THEN 1 ELSE 0 END)
        FROM StudentGroup sg
        JOIN sg.group g
        WHERE g.teacher IS NOT NULL
          AND (sg.isTrial = false OR sg.isTrial IS NULL)
        GROUP BY g.teacher.id
        """)
    List<Object[]> countRetentionStatsGroupedByTeacher(
        @Param("from") LocalDate from,
        @Param("to") LocalDate to);

    @Query("""
        SELECT COUNT(DISTINCT sg.student.id) FROM StudentGroup sg
        WHERE sg.firstLessonDate IS NOT NULL
          AND sg.firstLessonDate BETWEEN :from AND :to
        """)
    long countDistinctByFirstLessonDateBetween(
        @Param("from") LocalDate from,
        @Param("to") LocalDate to);

    @Query("""
        SELECT COUNT(DISTINCT sg.student.id) FROM StudentGroup sg
        WHERE sg.leaveDate IS NOT NULL
          AND sg.leaveDate BETWEEN :from AND :to
        """)
    long countDistinctLeftBetween(
        @Param("from") LocalDate from,
        @Param("to") LocalDate to);

    /**
     * Ledger ta'miri uchun: MONTHLY enrollmentlar (paymentType NULL ham MONTHLY
     * sanaladi — StudentGroup.onCreate shunday default beradi).
     * Faqat ID — har biri alohida tranzaksiyada qayta o'qiladi.
     */
    @Query("""
        SELECT sg.id FROM StudentGroup sg
        WHERE sg.paymentType IS NULL OR sg.paymentType = :type
        ORDER BY sg.id
        """)
    List<Long> findIdsByPaymentTypeOrNull(@Param("type") PaymentType type);

    /** I1: student.balance = barcha SG (yopilganlari ham) balanslari yig'indisi. */
    @Query("SELECT COALESCE(SUM(sg.balance), 0) FROM StudentGroup sg WHERE sg.student.id = :studentId")
    java.math.BigDecimal sumBalanceByStudentId(@Param("studentId") Long studentId);

    @Query("SELECT sg.student.id FROM StudentGroup sg WHERE sg.id = :id")
    Optional<Long> findStudentIdById(@Param("id") Long id);

    /** Kunlik snapshot: holati vaqtga bog'liq bo'lishi mumkin bo'lgan (qarzli) SG lar o'quvchilari. */
    @Query("""
        SELECT DISTINCT sg.student.id FROM StudentGroup sg
        WHERE sg.balance < 0
           OR sg.paymentStatus = com.crm.entity.enums.PaymentStatus.PENDING
           OR sg.paymentStatus = com.crm.entity.enums.PaymentStatus.OVERDUE
        """)
    List<Long> findStudentIdsForDailyRefresh();

    /** Qarzli SG lar (balance < 0) — o'quvchi va guruh bilan, N+1 siz. */
    @Query("""
        SELECT sg FROM StudentGroup sg
        JOIN FETCH sg.student s
        JOIN FETCH sg.group g
        WHERE sg.balance < 0 AND sg.debtSince IS NOT NULL
        """)
    List<StudentGroup> findWithDebt();


    @Query("SELECT sg FROM StudentGroup sg WHERE sg.isTrial = true AND sg.isActive = true")
    List<StudentGroup> findActiveTrials();

    /**
     * Kunlik accrual nomzodlari (§3.7): faol, MONTHLY (eski NULL ham), sinov va
     * muzlatilmagan, langari {@code date} gacha. Yakuniy qaror {@code AccrualCalculator.isAccruable} da.
     */
    @Query("""
        SELECT sg.id FROM StudentGroup sg
        WHERE sg.isActive = true
          AND (sg.isTrial = false OR sg.isTrial IS NULL)
          AND (sg.paymentType = :monthly OR sg.paymentType IS NULL)
          AND sg.frozenFrom IS NULL
          AND sg.paymentStartDate <= :date
        ORDER BY sg.id
        """)
    List<Long> findAccrualCandidateIds(@Param("date") LocalDate date, @Param("monthly") PaymentType monthly);
}
