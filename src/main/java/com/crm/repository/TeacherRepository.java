package com.crm.repository;

import com.crm.entity.Teacher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TeacherRepository extends JpaRepository<Teacher, Long> {

    List<Teacher> findByIsActiveTrue();
    Optional<Teacher> findByPhone(String phone);
    Optional<Teacher> findByUserId(Long userId);
    long countByIsActiveTrue();

    @Query("SELECT t FROM Teacher t WHERE t.isActive = true AND (" +
           "LOWER(t.firstName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(t.lastName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "t.phone LIKE CONCAT('%', :search, '%') OR " +
           "LOWER(COALESCE(t.teacherCode,'')) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<Teacher> searchTeachers(@Param("search") String search, Pageable pageable);

    @Query("SELECT t, COUNT(g), COUNT(DISTINCT sg.student) FROM Teacher t " +
           "LEFT JOIN t.groups g LEFT JOIN g.studentGroups sg " +
           "WHERE t.isActive = true GROUP BY t")
    List<Object[]> getTeacherPerformanceStats();

    @Query("SELECT t.status, COUNT(t) FROM Teacher t GROUP BY t.status")
    List<Object[]> countByStatus();
}
