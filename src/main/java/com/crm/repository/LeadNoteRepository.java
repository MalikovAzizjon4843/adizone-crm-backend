package com.crm.repository;

import com.crm.entity.LeadNote;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LeadNoteRepository extends JpaRepository<LeadNote, Long> {

    /** Muallif bir so'rovda keladi — lentada har izoh uchun N+1 bo'lmasin. */
    @EntityGraph(attributePaths = {"createdBy"})
    List<LeadNote> findByLead_IdOrderByCreatedAtDesc(Long leadId);

    @EntityGraph(attributePaths = {"createdBy", "lead"})
    Optional<LeadNote> findWithLeadById(Long id);
}
