package com.crm.repository;

import com.crm.entity.BillingJobRun;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BillingJobRunRepository extends JpaRepository<BillingJobRun, Long> {

    List<BillingJobRun> findByOrderByStartedAtDescIdDesc(Pageable pageable);
}
