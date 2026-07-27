package com.crm.service;

import com.crm.dto.request.AttendanceRequest;
import com.crm.dto.response.AttendanceResponse;
import com.crm.entity.*;
import com.crm.entity.enums.AttendanceStatus;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AttendanceService {

    private final AttendanceRepository attendanceRepository;
    private final StudentRepository studentRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final GroupRepository groupRepository;
    private final TelegramService telegramService;
    private final ParentRepository parentRepository;
    private final StudentPaymentLifecycleService studentPaymentLifecycleService;
    private final AttendanceUnlockRequestRepository attendanceUnlockRequestRepository;
    private final TeacherAccessService teacherAccessService;
    private final BalanceTransactionService balanceTransactionService;

    @Transactional
    public List<AttendanceResponse> markAttendance(AttendanceRequest request) {
        Group group = groupRepository.findById(request.getGroupId())
            .orElseThrow(() -> new ResourceNotFoundException("Group", request.getGroupId()));

        teacherAccessService.assertOwnsGroup(group);

        User marker = teacherAccessService.getCurrentUserOrThrow();

        LocalDate date = request.getDate();
        LocalDate today = LocalDate.now();
        boolean isAdmin = teacherAccessService.isCurrentUserAdmin();

        if (!isAdmin && date != null && date.isBefore(today)) {
            Teacher teacher = teacherAccessService.getCurrentTeacherOrThrow();

            boolean hasUnlock = attendanceUnlockRequestRepository.existsByTeacherIdAndGroupIdAndAttendanceDateAndStatus(
                teacher.getId(), request.getGroupId(), date, com.crm.entity.enums.UnlockRequestStatus.APPROVED
            );

            if (!hasUnlock) {
                throw new com.crm.exception.ForbiddenException("Bu kun uchun ruxsat kerak. Admindan so'rang.");
            }
        }

        List<AttendanceResponse> results = new ArrayList<>();

        for (AttendanceRequest.StudentAttendanceItem item : request.getAttendances()) {
            AttendanceStatus itemStatus = item.getStatus() != null ? item.getStatus() : AttendanceStatus.PRESENT;
            if (itemStatus == AttendanceStatus.ABSENT || itemStatus == AttendanceStatus.EXCUSED || itemStatus == AttendanceStatus.LATE) {
                boolean hasNote = (item.getNotes() != null && !item.getNotes().isBlank()) ||
                                  (item.getExcuseReason() != null && !item.getExcuseReason().isBlank());
                if (!hasNote) {
                    throw new com.crm.exception.BadRequestException("Sabab kiritilishi shart");
                }
            }

            Student student = studentRepository.findById(item.getStudentId())
                .orElseThrow(() -> new ResourceNotFoundException("Student", item.getStudentId()));

            Attendance attendance = attendanceRepository
                .findByStudentIdAndGroupIdAndAttendanceDate(
                    item.getStudentId(), request.getGroupId(), request.getDate())
                .orElse(null);

            AttendanceStatus previousStatus = attendance != null ? attendance.getStatus() : null;

            if (attendance == null) {
                attendance = Attendance.builder()
                    .student(student)
                    .group(group)
                    .attendanceDate(request.getDate())
                    .build();
            }

            attendance.setStatus(item.getStatus() != null ? item.getStatus() : AttendanceStatus.PRESENT);
            attendance.setNotes(item.getNotes());
            attendance.setMarkedBy(marker);

            if (attendance.getStatus() == AttendanceStatus.ABSENT
                    && Boolean.TRUE.equals(item.getExcused())) {
                attendance.setStatus(AttendanceStatus.EXCUSED);
                attendance.setExcused(true);
                attendance.setExcuseReason(item.getExcuseReason());
            } else {
                attendance.setExcused(item.getExcused() != null ? item.getExcused() : false);
                attendance.setExcuseReason(item.getExcuseReason());
            }

            Attendance saved = attendanceRepository.save(attendance);
            results.add(toResponse(saved));

            applyBalanceForAttendanceChange(student, group, previousStatus, saved);

            if (saved.getStatus() == AttendanceStatus.PRESENT || saved.getStatus() == AttendanceStatus.LATE) {
                studentPaymentLifecycleService.onLessonAttended(
                    item.getStudentId(), request.getGroupId(), request.getDate());
            } else if (saved.getStatus() == AttendanceStatus.ABSENT) {
                studentPaymentLifecycleService.onBillableAttendance(
                    item.getStudentId(), request.getGroupId());
            }

            if (saved.getStatus() == AttendanceStatus.ABSENT) {
                try {
                    List<Parent> parents = parentRepository
                        .findByStudentId(student.getId());

                    String message = telegramService.buildAttendanceMessage(
                        student.getFirstName() + " "
                            + student.getLastName(),
                        group.getGroupName(),
                        request.getDate().toString()
                    );

                    for (Parent parent : parents) {
                        if (parent.getTelegramChatId() != null
                            && !parent.getTelegramChatId().isBlank()) {
                            telegramService.sendMessage(
                                parent.getTelegramChatId(), message);
                        } else if (parent.getPhone() != null) {
                            log.info("Davomat xabari: {} → {}",
                                parent.getFullName(), message);
                        }
                    }
                } catch (Exception e) {
                    log.error("Davomat xabari yuborishda xatolik", e);
                }
            }
        }

        return results;
    }

    private void applyBalanceForAttendanceChange(
            Student student, Group group,
            AttendanceStatus previous, Attendance saved) {
        StudentGroup sg = studentGroupRepository
            .findByStudentIdAndGroupIdAndIsActiveTrue(student.getId(), group.getId())
            .orElse(null);
        if (sg == null) {
            return;
        }
        java.math.BigDecimal lessonPrice = PaymentScheduleService.resolveLessonPrice(sg);
        if (lessonPrice.compareTo(java.math.BigDecimal.ZERO) <= 0) {
            return;
        }

        boolean wasBillable = isBillable(previous);
        boolean nowBillable = isBillable(saved.getStatus());

        if (!wasBillable && nowBillable) {
            balanceTransactionService.record(
                sg,
                com.crm.entity.enums.BalanceTransactionType.LESSON_CHARGE,
                lessonPrice.negate(),
                saved.getId(),
                "Davomat: " + saved.getStatus() + " (" + saved.getAttendanceDate() + ")");
        } else if (wasBillable && !nowBillable) {
            balanceTransactionService.record(
                sg,
                com.crm.entity.enums.BalanceTransactionType.LESSON_REFUND,
                lessonPrice,
                saved.getId(),
                "Davomat o'zgardi: " + previous + " → " + saved.getStatus());
        }
    }

    private static boolean isBillable(AttendanceStatus status) {
        return status == AttendanceStatus.PRESENT
            || status == AttendanceStatus.ABSENT
            || status == AttendanceStatus.LATE;
    }

    @Transactional(readOnly = true)
    public List<AttendanceResponse> getGroupAttendance(Long groupId, LocalDate date) {
        teacherAccessService.assertOwnsGroup(groupId);
        LocalDate d = date != null ? date : LocalDate.now();
        List<Attendance> existing = attendanceRepository.findByGroup_IdAndAttendanceDate(groupId, d);
        
        if (!existing.isEmpty()) {
            return existing.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        }
        
        List<StudentGroup> activeStudents = studentGroupRepository.findByGroup_IdAndIsActiveTrue(groupId);
        
        return activeStudents.stream()
            .map(sg -> AttendanceResponse.builder()
                .studentId(sg.getStudent().getId())
                .studentName(sg.getStudent().getFirstName() + " " + sg.getStudent().getLastName())
                .groupId(groupId)
                .attendanceDate(d)
                .status(null)
                .notes("")
                .build())
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<AttendanceResponse> getStudentAttendance(Long studentId) {
        teacherAccessService.assertOwnsStudent(studentId);
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
            .excused(a.getExcused())
            .excuseReason(a.getExcuseReason())
            .createdAt(a.getCreatedAt())
            .build();
    }
}
