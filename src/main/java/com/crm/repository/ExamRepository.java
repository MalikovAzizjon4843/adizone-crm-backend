package com.crm.repository;

import com.crm.entity.Exam;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ExamRepository extends JpaRepository<Exam, Long> {
    Page<Exam> findByIsActiveTrue(Pageable pageable);
    Page<Exam> findByClassEntityId(Long classId, Pageable pageable);
    long countByIsActiveTrue();

    @Query("""
        SELECT e FROM Exam e
        WHERE e.isActive = true
          AND (e.teacher.id = :teacherId OR e.group.teacher.id = :teacherId)
        """)
    Page<Exam> findActiveByTeacherId(@Param("teacherId") Long teacherId, Pageable pageable);
}
