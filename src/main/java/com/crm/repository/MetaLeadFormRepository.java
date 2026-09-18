package com.crm.repository;

import com.crm.entity.MetaLeadForm;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface MetaLeadFormRepository extends JpaRepository<MetaLeadForm, Long> {

    Optional<MetaLeadForm> findByFormId(String formId);

    List<MetaLeadForm> findAllByOrderByLeadsCountDescIdAsc();

    /** {@code GET /api/meta/status} uchun — oxirgi sinxronizatsiya vaqti. */
    @Query("SELECT MAX(f.syncedAt) FROM MetaLeadForm f")
    Optional<Instant> findLastSyncedAt();
}
