package com.crm.repository;

import com.crm.entity.Parent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ParentRepository extends JpaRepository<Parent, Long> {

    @Query("SELECT sp.parent FROM StudentParent sp WHERE sp.student.id = :studentId AND sp.parent.isActive = true")
    List<Parent> findByStudentId(@Param("studentId") Long studentId);

    Page<Parent> findByIsActiveTrue(Pageable pageable);

    @Query("SELECT p FROM Parent p WHERE p.isActive = true AND (" +
           "LOWER(p.fullName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "COALESCE(p.phone,'') LIKE CONCAT('%', :search, '%'))")
    Page<Parent> searchParents(@Param("search") String search, Pageable pageable);
}
