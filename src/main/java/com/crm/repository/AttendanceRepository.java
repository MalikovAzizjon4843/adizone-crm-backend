package com.crm.repository;

import com.crm.entity.Attendance;
import com.crm.entity.enums.AttendanceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface AttendanceRepository extends JpaRepository<Attendance, Long> {

    List<Attendance> findByGroupIdAndAttendanceDateOrderByStudentLastName(
            Long groupId, LocalDate date);

    List<Attendance> findByGroup_IdAndAttendanceDate(Long groupId, LocalDate date);

    boolean existsByGroup_IdAndAttendanceDate(Long groupId, LocalDate date);

    @Query("SELECT DISTINCT a.attendanceDate FROM Attendance a "
           + "WHERE a.group.id = :groupId AND a.attendanceDate BETWEEN :from AND :to")
    List<LocalDate> findDistinctDatesByGroupAndDateBetween(
        @Param("groupId") Long groupId,
        @Param("from") LocalDate from,
        @Param("to") LocalDate to);

    List<Attendance> findByStudentIdOrderByAttendanceDateDesc(Long studentId);

    Optional<Attendance> findByStudentIdAndGroupIdAndAttendanceDate(
            Long studentId, Long groupId, LocalDate date);

    @Query("SELECT COUNT(a) FROM Attendance a WHERE a.student.id = :studentId " +
           "AND a.status = :status AND a.attendanceDate BETWEEN :from AND :to")
    long countByStudentAndStatusAndDateRange(@Param("studentId") Long studentId,
                                              @Param("status") AttendanceStatus status,
                                              @Param("from") LocalDate from,
                                              @Param("to") LocalDate to);

    @Query("SELECT a.status, COUNT(a) FROM Attendance a WHERE a.group.id = :groupId " +
           "AND a.attendanceDate BETWEEN :from AND :to GROUP BY a.status")
    List<Object[]> getAttendanceStatsByGroup(@Param("groupId") Long groupId,
                                              @Param("from") LocalDate from,
                                              @Param("to") LocalDate to);

    @Query("SELECT COUNT(a) FROM Attendance a WHERE a.status = 'PRESENT' " +
           "AND a.attendanceDate BETWEEN :from AND :to")
    long countPresentByDateRange(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT COUNT(a) FROM Attendance a WHERE a.attendanceDate BETWEEN :from AND :to")
    long countTotalByDateRange(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT a.status, COUNT(a) FROM Attendance a WHERE a.student.id = :studentId GROUP BY a.status")
    List<Object[]> countByStudentGrouped(@Param("studentId") Long studentId);

    @Query("SELECT MAX(a.attendanceDate) FROM Attendance a WHERE a.student.id = :studentId "
           + "AND a.group.id = :groupId AND a.status <> 'ABSENT'")
    LocalDate findLastAttendanceDate(
            @Param("studentId") Long studentId,
            @Param("groupId") Long groupId);

    @Query("SELECT COUNT(a) FROM Attendance a WHERE a.student.id = :studentId AND a.status = 'PRESENT'")
    long countPresentDaysForStudent(@Param("studentId") Long studentId);

    @Query("SELECT COUNT(a) FROM Attendance a WHERE a.group.id IN :groupIds "
           + "AND a.status = :status AND a.attendanceDate BETWEEN :from AND :to")
    long countByGroupIdsAndStatusAndDateBetween(@Param("groupIds") List<Long> groupIds,
                                                 @Param("status") AttendanceStatus status,
                                                 @Param("from") LocalDate from,
                                                 @Param("to") LocalDate to);

    @Query(value = "SELECT COUNT(*) FROM ("
           + "SELECT DISTINCT group_id, attendance_date FROM attendance "
           + "WHERE group_id IN (:groupIds) AND attendance_date BETWEEN :from AND :to"
           + ") sessions", nativeQuery = true)
    long countDistinctSessionsByGroupIdsAndDateBetween(@Param("groupIds") List<Long> groupIds,
                                                          @Param("from") LocalDate from,
                                                          @Param("to") LocalDate to);

    /**
     * Batch: teacherId, presentOrLateCount, totalAttendanceCount
     */
    @Query("""
        SELECT g.teacher.id,
               SUM(CASE WHEN a.status IN :presentStatuses THEN 1 ELSE 0 END),
               COUNT(a)
        FROM Attendance a
        JOIN a.group g
        WHERE g.teacher IS NOT NULL
          AND a.attendanceDate BETWEEN :from AND :to
        GROUP BY g.teacher.id
        """)
    List<Object[]> countAttendanceStatsGroupedByTeacher(
        @Param("from") LocalDate from,
        @Param("to") LocalDate to,
        @Param("presentStatuses") List<AttendanceStatus> presentStatuses);

    @Query("SELECT COUNT(a) FROM Attendance a WHERE a.student.id = :studentId "
           + "AND a.group.id = :groupId AND a.status IN :statuses")
    long countByStudentAndGroupAndStatuses(
        @Param("studentId") Long studentId,
        @Param("groupId") Long groupId,
        @Param("statuses") List<AttendanceStatus> statuses);

    @Query("SELECT COUNT(a) FROM Attendance a WHERE a.student.id = :studentId "
           + "AND a.group.id = :groupId AND a.status IN :statuses "
           + "AND a.attendanceDate >= :fromDate")
    long countByStudentAndGroupAndStatusesSince(
        @Param("studentId") Long studentId,
        @Param("groupId") Long groupId,
        @Param("statuses") List<AttendanceStatus> statuses,
        @Param("fromDate") LocalDate fromDate);

    /** Batch: userId, markedCount */
    @Query("""
        SELECT a.markedBy.id, COUNT(a)
        FROM Attendance a
        WHERE a.markedBy IS NOT NULL
          AND a.attendanceDate BETWEEN :from AND :to
        GROUP BY a.markedBy.id
        """)
    List<Object[]> countMarkedGroupedByUser(
        @Param("from") LocalDate from,
        @Param("to") LocalDate to);
}
