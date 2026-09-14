package com.crm.repository;

import com.crm.entity.LeadStage;
import com.crm.entity.enums.StageKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LeadStageRepository extends JpaRepository<LeadStage, Long> {

    List<LeadStage> findAllByOrderBySortOrderAscIdAsc();

    Optional<LeadStage> findByCode(String code);

    boolean existsByCode(String code);

    boolean existsByKind(StageKind kind);

    /** Yangi bosqich ro'yxat oxiriga tushishi uchun. */
    @Query("SELECT COALESCE(MAX(s.sortOrder), 0) FROM LeadStage s")
    int findMaxSortOrder();
}
