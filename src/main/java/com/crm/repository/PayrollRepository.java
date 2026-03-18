package com.crm.repository;

import com.crm.entity.Payroll;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PayrollRepository extends JpaRepository<Payroll, Long> {
    Page<Payroll> findAll(Pageable pageable);
    List<Payroll> findByTeacherId(Long teacherId);
    Optional<Payroll> findByTeacherIdAndMonthAndYear(Long teacherId, Integer month, Integer year);

    @Query("SELECT COUNT(p) FROM Payroll p WHERE p.status = 'PENDING'")
    long countPending();

    @Query("SELECT p FROM Payroll p WHERE p.year = :year AND p.month = :month")
    List<Payroll> findByPeriod(@Param("year") Integer year, @Param("month") Integer month);
}
