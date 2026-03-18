package com.crm.repository;

import com.crm.entity.Subject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SubjectRepository extends JpaRepository<Subject, Long> {
    Page<Subject> findByIsActiveTrue(Pageable pageable);
    List<Subject> findByClassEntityIdAndIsActiveTrue(Long classId);
    long countByIsActiveTrue();
}
