package com.crm.dashboard;

import com.crm.entity.Attendance;
import com.crm.entity.enums.AttendanceStatus;
import com.crm.repository.StudentGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Davomatdan dashboard belgilari (director-dashboard §1.1, §1.5): sinovda birinchi kelgan
 * kun ({@code trial_started_at}) va konvert qilingan lidning tashrifi ({@code visited_at}).
 * Faqat PRESENT/LATE — "kelgan".
 */
@Service
@RequiredArgsConstructor
public class AttendanceSignals {

    private final StudentGroupRepository studentGroupRepository;
    private final LeadFunnelTracker leadFunnelTracker;

    @Transactional(propagation = Propagation.MANDATORY)
    public void onAttendanceSaved(Attendance a) {
        if (a == null || a.getStudent() == null || a.getGroup() == null) {
            return;
        }
        if (a.getStatus() != AttendanceStatus.PRESENT && a.getStatus() != AttendanceStatus.LATE) {
            return;
        }
        studentGroupRepository
            .findByStudentIdAndGroupIdAndIsActiveTrue(a.getStudent().getId(), a.getGroup().getId())
            .ifPresent(sg -> {
                if (TrialTracking.markAttended(sg, a.getAttendanceDate())) {
                    studentGroupRepository.save(sg);
                }
            });
        leadFunnelTracker.onStudentAttended(a.getStudent(), a.getAttendanceDate());
    }
}
