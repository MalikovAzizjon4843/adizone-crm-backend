package com.crm.repository;

import com.crm.entity.LeadAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface LeadAssignmentRepository extends JpaRepository<LeadAssignment, Long> {

    Optional<LeadAssignment> findFirstByLeadIdAndUnassignedAtIsNullOrderByAssignedAtDescIdDesc(Long leadId);

    List<LeadAssignment> findByLeadIdOrderByAssignedAtAscIdAsc(Long leadId);

    boolean existsByLeadId(Long leadId);

    @Query("SELECT a FROM LeadAssignment a WHERE a.assignedAt >= :from AND a.assignedAt < :to ORDER BY a.assignedAt, a.id")
    List<LeadAssignment> findAssignedBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
