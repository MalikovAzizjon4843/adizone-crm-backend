package com.crm.service;

import com.crm.dto.request.AttendanceRequest;
import com.crm.dto.response.AttendanceResponse;
import com.crm.entity.*;
import com.crm.entity.enums.AttendanceStatus;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AttendanceService {

    private final AttendanceRepository attendanceRepository;
    private final StudentRepository studentRepository;
    private final GroupRepository groupRepository;
    private final UserRepository userRepository;

    @Transactional
    public List<AttendanceResponse> markAttendance(AttendanceRequest request) {
        Group group = groupRepository.findById(request.getGroupId())
            .orElseThrow(() -> new ResourceNotFoundException("Group", request.getGroupId()));

        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User marker = userRepository.findByUsername(username).orElse(null);

        List<AttendanceResponse> results = new ArrayList<>();

        for (AttendanceRequest.StudentAttendanceItem item : request.getAttendances()) {
            Student student = studentRepository.findById(item.getStudentId())
                .orElseThrow(() -> new ResourceNotFoundException("Student", item.getStudentId()));

            Attendance attendance = attendanceRepository
                .findByStudentIdAndGroupIdAndAttendanceDate(
                    item.getStudentId(), request.getGroupId(), request.getDate())
                .orElse(Attendance.builder()
                    .student(student)
                    .group(group)
                    .attendanceDate(request.getDate())
                    .build());

            attendance.setStatus(item.getStatus() != null ? item.getStatus() : AttendanceStatus.PRESENT);
            attendance.setNotes(item.getNotes());
            attendance.setMarkedBy(marker);

            Attendance saved = attendanceRepository.save(attendance);
            results.add(toResponse(saved));
        }

        return results;
    }

    @Transactional(readOnly = true)
    public List<AttendanceResponse> getGroupAttendance(Long groupId, LocalDate date) {
        List<Attendance> list = date != null
            ? attendanceRepository.findByGroupIdAndAttendanceDateOrderByStudentLastName(groupId, date)
            : attendanceRepository.findByGroupIdAndAttendanceDateOrderByStudentLastName(groupId, LocalDate.now());
        return list.stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<AttendanceResponse> getStudentAttendance(Long studentId) {
        return attendanceRepository.findByStudentIdOrderByAttendanceDateDesc(studentId)
            .stream().map(this::toResponse).collect(Collectors.toList());
    }

    private AttendanceResponse toResponse(Attendance a) {
        return AttendanceResponse.builder()
            .id(a.getId())
            .studentId(a.getStudent().getId())
            .studentName(a.getStudent().getFirstName() + " " + a.getStudent().getLastName())
            .groupId(a.getGroup().getId())
            .groupName(a.getGroup().getGroupName())
            .attendanceDate(a.getAttendanceDate())
            .status(a.getStatus())
            .notes(a.getNotes())
            .createdAt(a.getCreatedAt())
            .build();
    }
}
