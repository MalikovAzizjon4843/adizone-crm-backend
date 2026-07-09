package com.crm.repository;

import com.crm.entity.CashTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface CashTransactionRepository extends JpaRepository<CashTransaction, Long>,
        JpaSpecificationExecutor<CashTransaction> {

    long countByCashRegister_Id(Long cashRegisterId);
}
