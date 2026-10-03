package com.crm.repository;

import com.crm.entity.Exam;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

/** Ro'yxat ({@code GET /api/exams}) — {@code ExamService.examSpec} (filtrlar va o'qituvchi doirasi). */
@Repository
public interface ExamRepository extends JpaRepository<Exam, Long>, JpaSpecificationExecutor<Exam> {
    Page<Exam> findByClassEntityId(Long classId, Pageable pageable);
    long countByIsActiveTrue();
}
