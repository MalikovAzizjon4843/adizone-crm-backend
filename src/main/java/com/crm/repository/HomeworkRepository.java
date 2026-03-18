package com.crm.repository;

import com.crm.entity.Homework;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface HomeworkRepository extends JpaRepository<Homework, Long> {
    Page<Homework> findByIsActiveTrue(Pageable pageable);
    Page<Homework> findByTeacherId(Long teacherId, Pageable pageable);
    long countByIsActiveTrue();
}
