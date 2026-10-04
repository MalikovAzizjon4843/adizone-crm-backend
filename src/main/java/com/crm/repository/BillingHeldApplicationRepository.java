package com.crm.repository;

import com.crm.entity.BillingHeldApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BillingHeldApplicationRepository extends JpaRepository<BillingHeldApplication, Long> {

    Optional<BillingHeldApplication> findByIdempotencyKey(String idempotencyKey);

    List<BillingHeldApplication> findByStudentGroupIdOrderByIdDesc(Long studentGroupId);
}
