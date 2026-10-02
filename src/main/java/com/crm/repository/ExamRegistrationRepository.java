package com.crm.repository;

import com.crm.entity.ExamRegistration;
import com.crm.entity.enums.ExamPaymentStatus;
import com.crm.entity.enums.ExamRegistrationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExamRegistrationRepository extends JpaRepository<ExamRegistration, Long> {

    List<ExamRegistration> findByExamId(Long examId);

    Page<ExamRegistration> findByExamId(Long examId, Pageable pageable);

    /** Faol (CANCELLED emas) yozilish — bir o'quvchiga bittadan ko'p bo'lmaydi (V62 qisman UNIQUE). */
    @Query("""
        SELECT r FROM ExamRegistration r
        WHERE r.exam.id = :examId AND r.student.id = :studentId
          AND r.status <> com.crm.entity.enums.ExamRegistrationStatus.CANCELLED
        """)
    Optional<ExamRegistration> findActive(@Param("examId") Long examId, @Param("studentId") Long studentId);

    default boolean existsActive(Long examId, Long studentId) {
        return findActive(examId, studentId).isPresent();
    }

    boolean existsByExamIdAndStatus(Long examId, ExamRegistrationStatus status);

    boolean existsByExamIdAndStatusAndFeeStatus(Long examId, ExamRegistrationStatus status,
                                                ExamPaymentStatus feeStatus);

    Optional<ExamRegistration> findByIdempotencyKey(String idempotencyKey);
}
