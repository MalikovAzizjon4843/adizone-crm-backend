package com.crm.repository;

import com.crm.entity.Lead;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LeadRepository extends JpaRepository<Lead, Long> {

    Page<Lead> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);

    Page<Lead> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Optional<Lead> findByPhone(String phone);

    long countByStatus(String status);

    long countBySource(String source);

    @Query("SELECT l FROM Lead l WHERE " +
           "LOWER(l.fullName) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "l.phone LIKE CONCAT('%', :q, '%')")
    Page<Lead> search(@Param("q") String q, Pageable pageable);

    @Query("SELECT l.status, COUNT(l) FROM Lead l GROUP BY l.status")
    List<Object[]> countByStatusGrouped();

    @Query("SELECT l.source, COUNT(l) FROM Lead l GROUP BY l.source")
    List<Object[]> countBySourceGrouped();
}
