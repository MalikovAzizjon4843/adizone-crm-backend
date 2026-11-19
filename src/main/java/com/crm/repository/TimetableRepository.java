package com.crm.repository;

import com.crm.entity.Timetable;
import com.crm.entity.enums.GroupStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

@Repository
public interface TimetableRepository extends JpaRepository<Timetable, Long> {
    Page<Timetable> findAll(Pageable pageable);
    List<Timetable> findByClassEntityId(Long classId);
    List<Timetable> findByGroupId(Long groupId);

    boolean existsByGroup_IdAndDayOfWeek(Long groupId, String dayOfWeek);

    List<Timetable> findByTeacherId(Long teacherId);
    List<Timetable> findByDayOfWeek(String dayOfWeek);

    @Query("""
        SELECT t FROM Timetable t
        WHERE t.teacher.id = :teacherId OR t.group.teacher.id = :teacherId
        """)
    Page<Timetable> findByTeacherScope(@Param("teacherId") Long teacherId, Pageable pageable);

    /**
     * Jadval ro'yxati — faqat ochiq guruhlar ({@code statuses}, odatda ACTIVE/FORMING) va
     * {@code end_date} si {@code onDate} dan oldin tugamaganlari. Guruhsiz (eski sinf) yozuvlar qoladi.
     */
    @Query("""
        SELECT t FROM Timetable t LEFT JOIN t.group g
        WHERE g IS NULL
           OR (g.status IN :statuses AND (g.endDate IS NULL OR g.endDate >= :onDate))
        """)
    Page<Timetable> findOpen(@Param("statuses") Collection<GroupStatus> statuses,
                             @Param("onDate") LocalDate onDate,
                             Pageable pageable);

    /** {@link #findByTeacherScope} + {@link #findOpen} filtri. */
    @Query("""
        SELECT t FROM Timetable t LEFT JOIN t.group g
        WHERE (t.teacher.id = :teacherId OR g.teacher.id = :teacherId)
          AND (g IS NULL
               OR (g.status IN :statuses AND (g.endDate IS NULL OR g.endDate >= :onDate)))
        """)
    Page<Timetable> findOpenByTeacherScope(@Param("teacherId") Long teacherId,
                                           @Param("statuses") Collection<GroupStatus> statuses,
                                           @Param("onDate") LocalDate onDate,
                                           Pageable pageable);

    @Query("""
        SELECT DISTINCT t FROM Timetable t
        LEFT JOIN FETCH t.classroom
        LEFT JOIN FETCH t.group g
        LEFT JOIN FETCH g.course
        LEFT JOIN FETCH g.teacher
        LEFT JOIN FETCH t.teacher
        WHERE t.dayOfWeek = :dayOfWeek
        """)
    List<Timetable> findByDayOfWeekWithDetails(@Param("dayOfWeek") String dayOfWeek);

    @Query("""
        SELECT DISTINCT t FROM Timetable t
        LEFT JOIN FETCH t.classroom
        LEFT JOIN FETCH t.group g
        LEFT JOIN FETCH g.course
        LEFT JOIN FETCH g.teacher
        LEFT JOIN FETCH t.teacher
        WHERE t.dayOfWeek = :dayOfWeek
          AND (t.teacher.id = :teacherId OR g.teacher.id = :teacherId)
        """)
    List<Timetable> findByDayOfWeekWithDetailsForTeacher(
        @Param("dayOfWeek") String dayOfWeek,
        @Param("teacherId") Long teacherId);

    @Query("""
        SELECT t FROM Timetable t
        LEFT JOIN FETCH t.group g
        LEFT JOIN FETCH g.classroom
        WHERE t.classroom IS NULL
        """)
    List<Timetable> findAllWithNullClassroom();

    List<Timetable> findByClassroom_IdAndDayOfWeek(Long classroomId, String dayOfWeek);

    List<Timetable> findByTeacher_IdAndDayOfWeek(Long teacherId, String dayOfWeek);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM Timetable t WHERE t.group.id = :groupId")
    void deleteByGroup_Id(@Param("groupId") Long groupId);
}
