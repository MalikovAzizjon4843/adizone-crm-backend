package com.crm.repository;

import com.crm.entity.LeadStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LeadStatusHistoryRepository extends JpaRepository<LeadStatusHistory, Long> {

    List<LeadStatusHistory> findByLead_IdOrderByChangedAtDesc(Long leadId);
}
