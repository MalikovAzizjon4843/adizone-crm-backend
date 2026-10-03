package com.crm.service;

import com.crm.billing.LessonChargeService;

import com.crm.audit.AuditAction;
import com.crm.audit.Audited;
import com.crm.dto.request.AttendanceRequest;
import com.crm.dto.response.AbsenceNoticeResponse;
import com.crm.dto.response.AttendanceResponse;
import com.crm.dto.response.MissingAttendanceResponse;
import com.crm.dto.response.TeacherMissingAttendanceResponse;
import com.crm.entity.*;
import com.crm.entity.enums.AttendanceStatus;
import com.crm.entity.enums.PaymentType;
import com.crm.exception.BadRequestException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AttendanceService {


    private final AttendanceRepository attendanceRepository;
    private final StudentRepository studentRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final GroupRepository groupRepository;
    private final GroupScheduleService groupScheduleService;
    private final TelegramService telegramService;
    private final ParentRepository parentRepository;
    private final StudentPaymentLifecycleService studentPaymentLifecycleService;
    private final AttendanceUnlockRequestService attendanceUnlockRequestService;
    private final TeacherAccessService teacherAccessService;
    private final LessonChargeService lessonChargeService;
    private final com.crm.dashboard.AttendanceSignals attendanceSignals;
    private final AttendanceAccessService attendanceAccessService;
    private final LessonSubstitutionService lessonSubstitutionService;
    private final AbsenceNoticeService absenceNoticeService;
    private final AttendanceDueService attendanceDueService;

    @Transactional
    @Audited(action = AuditAction.UPDATE, entity = "Attendance",
        summary = "'Davomat belgilandi: ' + #result.size() + ' o''quvchi'",
        entityId = "#request.groupId")
    public List<AttendanceResponse> markAttendance(AttendanceRequest request) {
        Group group = groupRepository.findById(request.getGroupId())
            .orElseThrow(() -> new ResourceNotFoundException("Group", request.getGroupId()));

        LocalDate date = request.getDate();
        // Guruh o'qituvchisi yoki shu kungi o'rinbosar (leaves-exams-contracts §2.3)
        attendanceAccessService.assertCanMark(group, date);
        // "Bugun" — Asia/Tashkent (JVM default zonasi, CrmApplication.main).
        LocalDate today = LocalDate.now();
        if (date != null && date.isAfter(today)) {
            throw new BadRequestException("Kelajakdagi sana uchun davomat kiritib bo'lmaydi");
        }
        assertGroupHasLessonOnDate(group.getId(), date);

        User marker = teacherAccessService.getCurrentUserOrThrow();

        boolean isAdmin = teacherAccessService.isCurrentUserAdmin();

        if (!isAdmin && date != null && date.isBefore(today)) {
            Teacher teacher = teacherAccessService.getCurrentTeacherOrThrow();

            // APPROVED ruxsat muddatli (reviewedAt + app.attendance.unlock-valid-hours).
            switch (attendanceUnlockRequestService.unlockState(teacher.getId(), request.getGroupId(), date)) {
                case VALID -> {
                    // ruxsat amal qilmoqda
                }
                case EXPIRED -> throw new com.crm.exception.ForbiddenException(
                    "Ruxsat muddati tugagan, qayta so'rang");
                case NONE -> throw new com.crm.exception.ForbiddenException(
                    "Bu kun uchun ruxsat kerak. Admindan so'rang.");
            }
        }

        List<AttendanceResponse> results = new ArrayList<>();

        for (AttendanceRequest.StudentAttendanceItem item : request.getAttendances()) {
            // Holati yo'q element o'tkazib yuboriladi (avval PRESENT deb yozilardi — "belgilanmagan"
            // o'quvchi "keldi" bo'lib qolardi; eski panel hali yuborishi mumkin).
            if (item.getStatus() == null) {
                continue;
            }
            AttendanceStatus itemStatus = item.getStatus();
            if (itemStatus == AttendanceStatus.ABSENT || itemStatus == AttendanceStatus.EXCUSED || itemStatus == AttendanceStatus.LATE) {
                boolean hasNote = (item.getNotes() != null && !item.getNotes().isBlank()) ||
                                  (item.getExcuseReason() != null && !item.getExcuseReason().isBlank());
                if (!hasNote) {
                    throw new BadRequestException("Sabab kiritilishi shart");
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

            attendance.setStatus(itemStatus);
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

            // Billing v2 (§6.9): PER_LESSON ledger — davomat holatidan idempotent
            lessonChargeService.sync(saved);
            // Direktor dashboardi: sinov boshlanishi va lid tashrifi (§1.1, §1.5)
            attendanceSignals.onAttendanceSaved(saved);

            if (previousStatus != saved.getStatus()) {
                studentPaymentLifecycleService.onLessonAttended(
                    item.getStudentId(), request.getGroupId(), request.getDate());
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

        if (!results.isEmpty()) {
            // "Darsni X o'tdi" belgisi bo'lsa — dars o'tildi (CONDUCTED), shu tranzaksiyada
            lessonSubstitutionService.onAttendanceSaved(group.getId(), date, marker);
        }
        return results;
    }

    /**
     * Dars bo'lmagan kunga davomat kiritishni bloklaydi (admin uchun ham). Manba —
     * {@link GroupScheduleService} (GET /groups/{id}/lesson-days bilan bir xil).
     */
    void assertGroupHasLessonOnDate(Long groupId, LocalDate date) {
        if (date == null) {
            throw new BadRequestException("Sana majburiy");
        }
        if (!groupScheduleService.hasLessonOn(groupId, date)) {
            throw new BadRequestException(
                "Bu kunda guruhda dars yo'q (" + dayToUzbek(date.getDayOfWeek().name()) + ")");
        }
    }

    @Transactional(readOnly = true)
    public MissingAttendanceResponse getMissingAttendance(Long groupId, LocalDate from, LocalDate to) {
        Group group = groupRepository.findById(groupId)
            .orElseThrow(() -> new ResourceNotFoundException("Group", groupId));
        teacherAccessService.assertOwnsGroup(group);
        return buildMissingForGroup(group, from, to);
    }

    @Transactional(readOnly = true)
    public TeacherMissingAttendanceResponse getMyMissingAttendance(LocalDate from, LocalDate to) {
        Teacher teacher = teacherAccessService.getCurrentTeacherOrThrow();
        List<Group> groups = groupRepository.findByTeacherId(teacher.getId());
        List<MissingAttendanceResponse> items = new ArrayList<>();
        int total = 0;
        for (Group group : groups) {
            MissingAttendanceResponse m = buildMissingForGroup(group, from, to);
            if (m.getMissingCount() > 0) {
                items.add(m);
                total += m.getMissingCount();
            }
        }
        return TeacherMissingAttendanceResponse.builder()
            .totalMissing(total)
            .groups(items)
            .build();
    }

    /** Qaysi kunlar "belgilanmagan" — faqat {@link AttendanceDueService} (guruh boshlanishi, bayram, istisno, faol o'quvchi). */
    private MissingAttendanceResponse buildMissingForGroup(Group group, LocalDate from, LocalDate to) {
        List<MissingAttendanceResponse.MissingDateItem> missing = attendanceDueService.missing(group, from, to).stream()
            .map(l -> MissingAttendanceResponse.MissingDateItem.builder()
                .date(l.date())
                .dayOfWeek(l.dayOfWeek())
                .startTime(l.startTime())
                .build())
            .toList();

        return MissingAttendanceResponse.builder()
            .groupId(group.getId())
            .groupName(group.getGroupName())
            .missingDates(missing)
            .missingCount(missing.size())
            .build();
    }

    static String dayToUzbek(String day) {
        if (day == null) {
            return "";
        }
        return switch (day.toUpperCase(Locale.ROOT)) {
            case "MONDAY" -> "Dushanba";
            case "TUESDAY" -> "Seshanba";
            case "WEDNESDAY" -> "Chorshanba";
            case "THURSDAY" -> "Payshanba";
            case "FRIDAY" -> "Juma";
            case "SATURDAY" -> "Shanba";
            case "SUNDAY" -> "Yakshanba";
            default -> day;
        };
    }

    @Transactional(readOnly = true)
    public List<AttendanceResponse> getGroupAttendance(Long groupId, LocalDate date) {
        LocalDate d = date != null ? date : LocalDate.now();
        attendanceAccessService.assertCanRead(groupId, d);
        List<Attendance> existing = attendanceRepository.findByGroup_IdAndAttendanceDate(groupId, d);
        // O'quvchi yonida Mini App sabab bildirishi (telegram-platform §11.2)
        Map<Long, AbsenceNoticeResponse> notices = absenceNoticeService.byStudent(groupId, d);

        if (!existing.isEmpty()) {
            return existing.stream()
                .map(this::toResponse)
                .peek(r -> r.setAbsenceNotice(notices.get(r.getStudentId())))
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
                .absenceNotice(notices.get(sg.getStudent().getId()))
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
