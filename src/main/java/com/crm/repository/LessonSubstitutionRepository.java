package com.crm.repository;

import com.crm.entity.LessonSubstitution;
import com.crm.entity.enums.SubstitutionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface LessonSubstitutionRepository extends JpaRepository<LessonSubstitution, Long>,
        JpaSpecificationExecutor<LessonSubstitution> {

    /** Shu darsning faol (CANCELLED emas) belgisi — ko'pi bilan bitta. */
    @Query("""
        SELECT s FROM LessonSubstitution s JOIN FETCH s.substituteTeacher
        WHERE s.group.id = :groupId AND s.lessonDate = :date AND s.status <> :cancelled
        """)
    Optional<LessonSubstitution> findActive(@Param("groupId") Long groupId, @Param("date") LocalDate date,
                                            @Param("cancelled") SubstitutionStatus cancelled);

    default Optional<LessonSubstitution> findActive(Long groupId, LocalDate date) {
        return findActive(groupId, date, SubstitutionStatus.CANCELLED);
    }

    /** O'rinbosar sifatidagi faol belgilari (sana oralig'ida). */
    @Query("""
        SELECT s FROM LessonSubstitution s JOIN FETCH s.group
        WHERE s.substituteTeacher.id = :teacherId AND s.lessonDate BETWEEN :from AND :to AND s.status <> :cancelled
        ORDER BY s.lessonDate, s.id
        """)
    List<LessonSubstitution> findBySubstitute(@Param("teacherId") Long teacherId, @Param("from") LocalDate from,
                                              @Param("to") LocalDate to, @Param("cancelled") SubstitutionStatus cancelled);

    /** O'qituvchi asosiy yoki o'rinbosar bo'lgan faol belgilar (sana oralig'ida) — {@code GET /my}. */
    @Query("""
        SELECT s FROM LessonSubstitution s
          JOIN FETCH s.group JOIN FETCH s.originalTeacher JOIN FETCH s.substituteTeacher
        WHERE (s.originalTeacher.id = :teacherId OR s.substituteTeacher.id = :teacherId)
          AND s.lessonDate BETWEEN :from AND :to AND s.status <> :cancelled
        ORDER BY s.lessonDate, s.id
        """)
    List<LessonSubstitution> findByTeacher(@Param("teacherId") Long teacherId, @Param("from") LocalDate from,
                                           @Param("to") LocalDate to, @Param("cancelled") SubstitutionStatus cancelled);

    /** Payroll: o'tilgan (CONDUCTED) darslar. */
    @Query("""
        SELECT s FROM LessonSubstitution s JOIN FETCH s.group JOIN FETCH s.originalTeacher
        WHERE s.substituteTeacher.id = :teacherId AND s.status = :status AND s.lessonDate BETWEEN :from AND :to
        ORDER BY s.lessonDate, s.id
        """)
    List<LessonSubstitution> findBySubstituteAndStatus(@Param("teacherId") Long teacherId,
                                                       @Param("status") SubstitutionStatus status,
                                                       @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Dashboard: oraliqdagi barcha faol belgilar. */
    @Query("""
        SELECT s FROM LessonSubstitution s JOIN FETCH s.substituteTeacher
        WHERE s.lessonDate BETWEEN :from AND :to AND s.status <> :cancelled
        """)
    List<LessonSubstitution> findActiveBetween(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                               @Param("cancelled") SubstitutionStatus cancelled);

    List<LessonSubstitution> findByLeaveRequestIdAndStatus(Long leaveRequestId, SubstitutionStatus status);
}
