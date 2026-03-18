package com.crm.repository;

import com.crm.entity.StudentParent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StudentParentRepository extends JpaRepository<StudentParent, Long> {

    List<StudentParent> findByStudentId(Long studentId);
    List<StudentParent> findByParentId(Long parentId);
    Optional<StudentParent> findByStudentIdAndParentId(Long studentId, Long parentId);
    boolean existsByStudentIdAndParentId(Long studentId, Long parentId);
}
