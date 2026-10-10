package com.crm.repository;

import com.crm.entity.Group;
import com.crm.entity.enums.GroupStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface GroupRepository extends JpaRepository<Group, Long> {

    List<Group> findByGroupNameAndStatus(String groupName, GroupStatus status);

    /** Excel import uchun: barcha guruhlar kursi bilan birga (LAZY course N+1 bo'lmasin). */
    @Query("SELECT g FROM Group g LEFT JOIN FETCH g.course")
    List<Group> findAllWithCourse();

    List<Group> findByStatus(GroupStatus status);

    Page<Group> findByStatus(GroupStatus status, Pageable pageable);

    List<Group> findByCourseId(Long courseId);

    List<Group> findByTeacherId(Long teacherId);

    List<Group> findByTeacher_IdAndStatus(Long teacherId, GroupStatus status);

    List<Group> findByTeacher_IdAndStatusInOrderByIdAsc(Long teacherId, Collection<GroupStatus> statuses);

    /** Batch: teacherId, groupCount */
    @Query("""
        SELECT g.teacher.id, COUNT(g)
        FROM Group g
        WHERE g.teacher IS NOT NULL
        GROUP BY g.teacher.id
        """)
    List<Object[]> countGroupsGroupedByTeacher();

    long countByStatus(GroupStatus status);

    @Query("SELECT g FROM Group g WHERE g.currentStudents < g.maxStudents AND g.status = 'ACTIVE'")
    List<Group> findGroupsWithAvailableSlots();

    @Query("SELECT g, COUNT(sg) FROM Group g LEFT JOIN g.studentGroups sg " +
           "WHERE g.status = 'ACTIVE' GROUP BY g")
    List<Object[]> getGroupFillRates();

    /**
     * Diqqat talab qiladigan guruhlar (billing-v2 R3, §14.3): holati {@code statuses} da, {@code end_date} bugun yoki
     * o'tgan, yoki boshlanish sanasidan oldin (noto'g'ri kiritilgan) — shunday guruhda yangi davr yozilmaydi.
     */
    @Query(value = """
        SELECT g FROM Group g LEFT JOIN FETCH g.course
        WHERE g.status IN :statuses AND g.endDate IS NOT NULL
          AND (g.endDate <= :today OR g.endDate <= g.startDate)
        ORDER BY g.endDate, g.id
        """)
    List<Group> findEndDateAttention(@Param(value = "statuses") Collection<GroupStatus> statuses,
                                     @Param(value = "today") java.time.LocalDate today);
}
