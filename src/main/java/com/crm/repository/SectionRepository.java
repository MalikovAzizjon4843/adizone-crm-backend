package com.crm.repository;

import com.crm.entity.Section;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SectionRepository extends JpaRepository<Section, Long> {
    Page<Section> findByIsActiveTrue(Pageable pageable);
    List<Section> findByClassEntityIdAndIsActiveTrue(Long classId);
}
