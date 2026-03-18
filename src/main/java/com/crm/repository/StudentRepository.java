package com.crm.repository;

import com.crm.entity.Student;
import com.crm.entity.enums.MarketingSource;
import com.crm.entity.enums.StudentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface StudentRepository extends JpaRepository<Student, Long> {

    Optional<Student> findByPhone(String phone);

    Page<Student> findByStatus(StudentStatus status, Pageable pageable);
    List<Student> findByStatus(StudentStatus status);

    long countByStatus(StudentStatus status);
    long countByMarketingSource(MarketingSource source);

    @Query("SELECT s FROM Student s WHERE " +
           "LOWER(s.firstName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(s.lastName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "s.phone LIKE CONCAT('%', :search, '%') OR " +
           "LOWER(COALESCE(s.admissionNumber,'')) LIKE LOWER(CONCAT('%', :search, '%'))")
    Page<Student> searchStudents(@Param("search") String search, Pageable pageable);

    @Query("SELECT s FROM Student s WHERE s.status = :status AND (" +
           "LOWER(s.firstName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(s.lastName) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<Student> searchByStatusAndName(@Param("status") StudentStatus status,
                                        @Param("search") String search, Pageable pageable);

    @Query("SELECT COUNT(s) FROM Student s WHERE s.createdAt >= :from AND s.createdAt <= :to")
    long countByCreatedAtBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT s.marketingSource, COUNT(s) FROM Student s GROUP BY s.marketingSource")
    List<Object[]> countByMarketingSourceGrouped();

    @Query("SELECT FUNCTION('MONTH', s.createdAt), FUNCTION('YEAR', s.createdAt), COUNT(s) " +
           "FROM Student s WHERE s.createdAt >= :from " +
           "GROUP BY FUNCTION('YEAR', s.createdAt), FUNCTION('MONTH', s.createdAt) " +
           "ORDER BY FUNCTION('YEAR', s.createdAt), FUNCTION('MONTH', s.createdAt)")
    List<Object[]> getStudentGrowthByMonth(@Param("from") LocalDateTime from);

    @Query("SELECT s.gender, COUNT(s) FROM Student s WHERE s.gender IS NOT NULL GROUP BY s.gender")
    List<Object[]> countByGender();

    @Query("SELECT s.status, COUNT(s) FROM Student s GROUP BY s.status")
    List<Object[]> countByStatusGrouped();
}
