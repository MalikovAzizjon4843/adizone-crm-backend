package com.crm.repository;

import com.crm.entity.ClassEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ClassRepository extends JpaRepository<ClassEntity, Long> {
    Page<ClassEntity> findByIsActiveTrue(Pageable pageable);
    long countByIsActiveTrue();
}
