package com.crm.repository;

import com.crm.entity.Timetable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TimetableRepository extends JpaRepository<Timetable, Long> {
    Page<Timetable> findAll(Pageable pageable);
    List<Timetable> findByClassEntityId(Long classId);
    List<Timetable> findByGroupId(Long groupId);
    List<Timetable> findByTeacherId(Long teacherId);
    List<Timetable> findByDayOfWeek(String dayOfWeek);

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
