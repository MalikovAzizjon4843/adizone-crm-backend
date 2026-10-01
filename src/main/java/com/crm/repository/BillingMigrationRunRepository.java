package com.crm.repository;

import com.crm.entity.BillingMigrationRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BillingMigrationRunRepository extends JpaRepository<BillingMigrationRun, Long> {

    List<BillingMigrationRun> findAllByOrderByIdDesc();
}
