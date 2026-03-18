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
}
