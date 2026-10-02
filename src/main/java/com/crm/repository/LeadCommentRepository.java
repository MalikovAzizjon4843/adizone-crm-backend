package com.crm.repository;

import com.crm.entity.LeadComment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface LeadCommentRepository extends JpaRepository<LeadComment, Long> {

    /** Muallif bir so'rovda keladi — lentada har izoh uchun N+1 bo'lmasin. */
    @EntityGraph(attributePaths = {"author"})
    List<LeadComment> findByLeadIdOrderByCreatedAtDesc(Long leadId);

    Page<LeadComment> findByLeadIdOrderByCreatedAtDesc(Long leadId, Pageable pageable);

    long countByLeadId(Long leadId);

    @Query("SELECT lc.lead.id, COUNT(lc) FROM LeadComment lc WHERE lc.lead.id IN :leadIds GROUP BY lc.lead.id")
    List<Object[]> countByLeadIds(@Param("leadIds") Collection<Long> leadIds);

    /**
     * Har lid uchun eng oxirgi izoh matni (created_at, teng bo'lsa id bo'yicha). Avval PostgreSQL ga xos
     * {@code DISTINCT ON} edi — H2 testlarida {@code GET /api/leads} 500 berardi; NOT EXISTS ikkala bazada ishlaydi.
     */
    @Query(value = """
        SELECT c.lead_id, c.text
        FROM lead_comments c
        WHERE c.lead_id IN (:leadIds)
          AND NOT EXISTS (SELECT 1 FROM lead_comments n
                          WHERE n.lead_id = c.lead_id
                            AND (n.created_at > c.created_at OR (n.created_at = c.created_at AND n.id > c.id)))
        """, nativeQuery = true)
    List<Object[]> findLatestTextByLeadIds(@Param("leadIds") Collection<Long> leadIds);
}
