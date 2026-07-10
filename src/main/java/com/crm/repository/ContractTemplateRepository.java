package com.crm.repository;

import com.crm.entity.ContractTemplate;
import com.crm.entity.enums.ContractType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ContractTemplateRepository extends JpaRepository<ContractTemplate, Long> {

    Optional<ContractTemplate> findByIsDefaultTrue();

    List<ContractTemplate> findByType(ContractType type);
}
