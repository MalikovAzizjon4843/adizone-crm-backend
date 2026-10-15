package com.crm.repository;

import com.crm.entity.CashHandoverLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface CashHandoverLineRepository extends JpaRepository<CashHandoverLine, Long> {

    List<CashHandoverLine> findByHandoverIdOrderByIdAsc(Long handoverId);

    List<CashHandoverLine> findByHandoverIdIn(Collection<Long> handoverIds);
}
