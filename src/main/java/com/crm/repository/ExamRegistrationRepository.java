package com.crm.repository;

import com.crm.entity.ExamRegistration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExamRegistrationRepository extends JpaRepository<ExamRegistration, Long> {

    List<ExamRegistration> findByExamId(Long examId);

    Optional<ExamRegistration> findByExamIdAndStudentId(Long examId, Long studentId);

    boolean existsByExamIdAndStudentId(Long examId, Long studentId);
}
