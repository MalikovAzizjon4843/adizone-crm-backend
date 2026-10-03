package com.crm.repository;

import com.crm.entity.Homework;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

/** Ro'yxat filtrlari — {@code HomeworkService.listSpec} (phase6-api §4). */
@Repository
public interface HomeworkRepository extends JpaRepository<Homework, Long>, JpaSpecificationExecutor<Homework> {
    Page<Homework> findByGroupIdAndIsActiveTrue(Long groupId, Pageable pageable);
    Page<Homework> findByTeacherId(Long teacherId, Pageable pageable);
    long countByIsActiveTrue();
}
