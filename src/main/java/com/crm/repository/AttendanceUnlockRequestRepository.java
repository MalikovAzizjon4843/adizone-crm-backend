package com.crm.repository;

import com.crm.entity.AttendanceUnlockRequest;
import com.crm.entity.enums.UnlockRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface AttendanceUnlockRequestRepository extends JpaRepository<AttendanceUnlockRequest, Long> {

    boolean existsByTeacherIdAndGroupIdAndAttendanceDateAndStatus(
        Long teacherId, Long groupId, LocalDate attendanceDate, UnlockRequestStatus status
    );

    List<AttendanceUnlockRequest> findByStatusOrderByCreatedAtDesc(UnlockRequestStatus status);

    List<AttendanceUnlockRequest> findByTeacherIdOrderByCreatedAtDesc(Long teacherId);

    long countByStatus(UnlockRequestStatus status);
}
