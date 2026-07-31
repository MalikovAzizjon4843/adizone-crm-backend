package com.crm.repository;

import com.crm.entity.AttendanceUnlockRequest;
import com.crm.entity.enums.UnlockRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AttendanceUnlockRequestRepository extends JpaRepository<AttendanceUnlockRequest, Long> {

    boolean existsByTeacherIdAndGroupIdAndAttendanceDateAndStatus(
        Long teacherId, Long groupId, LocalDate attendanceDate, UnlockRequestStatus status
    );

    List<AttendanceUnlockRequest> findByStatusOrderByCreatedAtDesc(UnlockRequestStatus status);

    List<AttendanceUnlockRequest> findByTeacherIdOrderByCreatedAtDesc(Long teacherId);

    long countByStatus(UnlockRequestStatus status);

    /** Batch: userId (teacher.user), requestCount */
    @Query("""
        SELECT t.user.id, COUNT(r)
        FROM AttendanceUnlockRequest r
        JOIN r.teacher t
        WHERE t.user IS NOT NULL
          AND r.createdAt >= :from
          AND r.createdAt < :toExclusive
        GROUP BY t.user.id
        """)
    List<Object[]> countGroupedByTeacherUser(
        @Param("from") LocalDateTime from,
        @Param("toExclusive") LocalDateTime toExclusive);
}
