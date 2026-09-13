package com.crm.repository;

import com.crm.entity.LeadStatusHistory;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LeadStatusHistoryRepository extends JpaRepository<LeadStatusHistory, Long> {

    /** O'zgartirgan odam bir so'rovda keladi — har qator uchun N+1 bo'lmasin. */
    @EntityGraph(attributePaths = {"changedBy"})
    List<LeadStatusHistory> findByLead_IdOrderByChangedAtDesc(Long leadId);
}
