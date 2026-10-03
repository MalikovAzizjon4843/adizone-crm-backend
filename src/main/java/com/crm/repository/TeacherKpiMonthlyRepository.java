package com.crm.repository;

import com.crm.entity.TeacherKpiMonthly;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface TeacherKpiMonthlyRepository extends JpaRepository<TeacherKpiMonthly, Long> {

    List<TeacherKpiMonthly> findByMonthStart(LocalDate monthStart);

    Optional<TeacherKpiMonthly> findByTeacherIdAndMonthStart(Long teacherId, LocalDate monthStart);
}
