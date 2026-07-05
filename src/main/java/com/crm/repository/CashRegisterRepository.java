package com.crm.repository;

import com.crm.entity.CashRegister;
import com.crm.entity.enums.CashRegisterStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CashRegisterRepository extends JpaRepository<CashRegister, Long> {

    List<CashRegister> findByStatus(CashRegisterStatus status);
}
