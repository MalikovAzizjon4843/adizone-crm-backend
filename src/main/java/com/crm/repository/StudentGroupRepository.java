package com.crm.repository;

import com.crm.entity.StudentGroup;
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
public interface StudentGroupRepository extends JpaRepository<StudentGroup, Long> {

    List<StudentGroup> findByStudentId(Long studentId);

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

    @Query("SELECT sg FROM StudentGroup sg WHERE sg.isActive = true AND sg.leaveDate IS NULL "
           + "AND sg.nextPaymentDate < :today "
           + "AND sg.student.status = 'ACTIVE'")
    List<StudentGroup> findDebtors(@Param("today") LocalDate today);

    @Query("SELECT sg FROM StudentGroup sg WHERE sg.isActive = true AND sg.leaveDate IS NULL "
           + "AND sg.nextPaymentDate BETWEEN :today AND :soon")
    List<StudentGroup> findPaymentsDueSoon(@Param("today") LocalDate today,
                                            @Param("soon") LocalDate soon);

    @Query("SELECT COUNT(sg) FROM StudentGroup sg WHERE sg.isActive = true AND sg.leaveDate IS NULL")
    long countActiveEnrollments();

    @Query("SELECT sg FROM StudentGroup sg WHERE sg.group.id = :groupId AND sg.paymentStatus = 'SUSPENDED'")
    List<StudentGroup> findSuspendedByGroupId(@Param("groupId") Long groupId);

    @Query("SELECT sg FROM StudentGroup sg WHERE sg.paymentStatus = 'SUSPENDED' "
           + "AND sg.suspendedAt IS NOT NULL AND sg.suspendedAt <= :cutoff")
    List<StudentGroup> findSuspendedOnOrBefore(@Param("cutoff") LocalDateTime cutoff);

    @Query("SELECT sg FROM StudentGroup sg WHERE sg.paymentStatus = :paymentStatus "
           + "AND sg.isActive = true AND sg.leaveDate IS NULL")
    List<StudentGroup> findByPaymentStatusAndIsActiveTrue(@Param("paymentStatus") String paymentStatus);

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
           + "AND sg.joinDate BETWEEN :from AND :to AND sg.paymentStatus = 'PAID'")
    long countPaidByGroupIdsAndJoinDateBetween(@Param("groupIds") List<Long> groupIds,
                                                @Param("from") LocalDate from,
                                                @Param("to") LocalDate to);

    /** Diagnostika: isActive=true lekin leaveDate to'ldirilgan (nomuvofiq). */
    @Query("SELECT COUNT(sg) FROM StudentGroup sg WHERE sg.isActive = true AND sg.leaveDate IS NOT NULL")
    long countActiveFlagButHasLeaveDate();

    /** Diagnostika: leaveDate null lekin isActive=false (nomuvofiq). */
    @Query("SELECT COUNT(sg) FROM StudentGroup sg WHERE sg.isActive = false AND sg.leaveDate IS NULL")
    long countInactiveFlagButNoLeaveDate();
}
