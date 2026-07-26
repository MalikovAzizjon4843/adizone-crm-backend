package com.crm.repository;

import com.crm.entity.CashTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

@Repository
public interface CashTransactionRepository extends JpaRepository<CashTransaction, Long>,
        JpaSpecificationExecutor<CashTransaction> {

    long countByCashRegister_Id(Long cashRegisterId);

    @Query("SELECT c FROM CashTransaction c WHERE c.teacher.id = :teacherId AND c.cashRegister.id = :cashRegisterId AND c.type = com.crm.entity.enums.CashTransactionType.EXPENSE AND c.note LIKE :notePattern")
    List<CashTransaction> findPayrollTransactions(
        @Param("teacherId") Long teacherId,
        @Param("cashRegisterId") Long cashRegisterId,
        @Param("notePattern") String notePattern
    );
}
