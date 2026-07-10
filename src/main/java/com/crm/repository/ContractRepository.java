package com.crm.repository;

import com.crm.entity.Contract;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ContractRepository extends JpaRepository<Contract, Long>,
        JpaSpecificationExecutor<Contract> {

    List<Contract> findByStudentId(Long studentId);
}
