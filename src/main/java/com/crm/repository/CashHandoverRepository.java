package com.crm.repository;

import com.crm.entity.CashHandover;
import com.crm.entity.enums.CashHandoverStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface CashHandoverRepository extends JpaRepository<CashHandover, Long>,
        JpaSpecificationExecutor<CashHandover> {

    boolean existsByCashRegisterIdAndStatus(Long cashRegisterId, CashHandoverStatus status);

    Optional<CashHandover> findByIdempotencyKey(String idempotencyKey);
}
