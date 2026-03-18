package com.crm.repository;

import com.crm.entity.Teacher;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface TeacherRepository extends JpaRepository<Teacher, Long> {
    List<Teacher> findByIsActiveTrue();
    Optional<Teacher> findByPhone(String phone);
    Optional<Teacher> findByUserId(Long userId);

    @Query("SELECT t, COUNT(g), COUNT(DISTINCT sg.student) FROM Teacher t " +
           "LEFT JOIN t.groups g LEFT JOIN g.studentGroups sg " +
           "WHERE t.isActive = true GROUP BY t")
    List<Object[]> getTeacherPerformanceStats();
}
