package com.crm.repository;

import com.crm.entity.StudentPaymentPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StudentPaymentPlanRepository extends JpaRepository<StudentPaymentPlan, Long> {

    List<StudentPaymentPlan> findByStudent_Id(Long studentId);
}
