package com.crm.repository;

import com.crm.entity.StudentGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface StudentGroupRepository extends JpaRepository<StudentGroup, Long> {

    List<StudentGroup> findByStudentId(Long studentId);

    List<StudentGroup> findByStudentIdOrderByJoinDateDesc(Long studentId);

    List<StudentGroup> findByGroupId(Long groupId);

    List<StudentGroup> findByStudentIdAndIsActiveTrue(Long studentId);

    Optional<StudentGroup> findByStudentIdAndGroupIdAndIsActiveTrue(Long studentId, Long groupId);

    boolean existsByStudentIdAndGroupIdAndIsActiveTrue(Long studentId, Long groupId);

    @Query("SELECT sg FROM StudentGroup sg WHERE sg.isActive = true AND sg.student.status = 'ACTIVE'")
    List<StudentGroup> findAllActiveEnrollments();

    // Find all debtors: next_payment_date < today
    @Query("SELECT sg FROM StudentGroup sg WHERE sg.isActive = true " +
           "AND sg.nextPaymentDate < :today " +
           "AND sg.student.status = 'ACTIVE'")
    List<StudentGroup> findDebtors(@Param("today") LocalDate today);

    // Students whose payment is due soon (within next 3 days)
    @Query("SELECT sg FROM StudentGroup sg WHERE sg.isActive = true " +
           "AND sg.nextPaymentDate BETWEEN :today AND :soon")
    List<StudentGroup> findPaymentsDueSoon(@Param("today") LocalDate today,
                                            @Param("soon") LocalDate soon);

    @Query("SELECT COUNT(sg) FROM StudentGroup sg WHERE sg.isActive = true")
    long countActiveEnrollments();
}
