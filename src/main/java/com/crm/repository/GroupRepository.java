package com.crm.repository;

import com.crm.entity.Group;
import com.crm.entity.enums.GroupStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GroupRepository extends JpaRepository<Group, Long> {

    List<Group> findByStatus(GroupStatus status);

    Page<Group> findByStatus(GroupStatus status, Pageable pageable);

    List<Group> findByCourseId(Long courseId);

    List<Group> findByTeacherId(Long teacherId);

    long countByStatus(GroupStatus status);

    @Query("SELECT g FROM Group g WHERE g.currentStudents < g.maxStudents AND g.status = 'ACTIVE'")
    List<Group> findGroupsWithAvailableSlots();

    @Query("SELECT g, COUNT(sg) FROM Group g LEFT JOIN g.studentGroups sg " +
           "WHERE g.status = 'ACTIVE' GROUP BY g")
    List<Object[]> getGroupFillRates();
}
