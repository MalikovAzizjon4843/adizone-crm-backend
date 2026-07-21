package com.crm.repository;

import com.crm.entity.LeadComment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface LeadCommentRepository extends JpaRepository<LeadComment, Long> {

    List<LeadComment> findByLeadIdOrderByCreatedAtDesc(Long leadId);

    Page<LeadComment> findByLeadIdOrderByCreatedAtDesc(Long leadId, Pageable pageable);

    long countByLeadId(Long leadId);

    @Query("SELECT lc.lead.id, COUNT(lc) FROM LeadComment lc WHERE lc.lead.id IN :leadIds GROUP BY lc.lead.id")
    List<Object[]> countByLeadIds(@Param("leadIds") Collection<Long> leadIds);

    @Query(value = """
        SELECT DISTINCT ON (lead_id) lead_id, text
        FROM lead_comments
        WHERE lead_id IN (:leadIds)
        ORDER BY lead_id, created_at DESC
        """, nativeQuery = true)
    List<Object[]> findLatestTextByLeadIds(@Param("leadIds") Collection<Long> leadIds);
}
