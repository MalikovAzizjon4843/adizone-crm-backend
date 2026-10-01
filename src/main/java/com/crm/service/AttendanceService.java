package com.crm.service;

import com.crm.billing.LessonChargeService;

import com.crm.audit.AuditAction;
import com.crm.audit.Audited;
import com.crm.dto.request.AttendanceRequest;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AttendanceService {

    private static final int DEFAULT_MISSING_DAYS = 30;

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

    @Transactional
    @Audited(action = AuditAction.UPDATE, entity = "Attendance",
        summary = "'Davomat belgilandi: ' + #result.size() + ' o''quvchi'",
        entityId = "#request.groupId")
    public List<AttendanceResponse> markAttendance(AttendanceRequest request) {
        Group group = groupRepository.findById(request.getGroupId())
            .orElseThrow(() -> new ResourceNotFoundException("Group", request.getGroupId()));

        teacherAccessService.assertOwnsGroup(group);

        LocalDate date = request.getDate();
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

    private MissingAttendanceResponse buildMissingForGroup(Group group, LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now();
        LocalDate rangeTo = to != null ? to : today;
        LocalDate rangeFrom = from != null ? from : today.minusDays(DEFAULT_MISSING_DAYS);
        if (rangeTo.isAfter(today)) {
            rangeTo = today;
        }
        if (rangeFrom.isAfter(rangeTo)) {
            return MissingAttendanceResponse.builder()
                .groupId(group.getId())
                .groupName(group.getGroupName())
                .missingDates(List.of())
                .missingCount(0)
                .build();
        }

        Map<String, String> startByDay = loadLessonStartTimes(group.getId());
        if (startByDay.isEmpty()) {
            return MissingAttendanceResponse.builder()
                .groupId(group.getId())
                .groupName(group.getGroupName())
                .missingDates(List.of())
                .missingCount(0)
                .build();
        }

        Set<LocalDate> marked = new HashSet<>(
            attendanceRepository.findDistinctDatesByGroupAndDateBetween(
                group.getId(), rangeFrom, rangeTo));

        List<MissingAttendanceResponse.MissingDateItem> missing = new ArrayList<>();
        for (LocalDate d = rangeFrom; !d.isAfter(rangeTo); d = d.plusDays(1)) {
            String day = d.getDayOfWeek().name();
            if (!startByDay.containsKey(day)) {
                continue;
            }
            if (marked.contains(d)) {
                continue;
            }
            missing.add(MissingAttendanceResponse.MissingDateItem.builder()
                .date(d)
                .dayOfWeek(day)
                .startTime(startByDay.get(day))
                .build());
        }

        return MissingAttendanceResponse.builder()
            .groupId(group.getId())
            .groupName(group.getGroupName())
            .missingDates(missing)
            .missingCount(missing.size())
            .build();
    }

    /** dayOfWeek → startTime (birinchi topilgan). Manba — {@link GroupScheduleService}. */
    private Map<String, String> loadLessonStartTimes(Long groupId) {
        Map<String, String> map = new LinkedHashMap<>();
        for (GroupScheduleService.LessonSlot slot : groupScheduleService.lessonSlots(groupId)) {
            map.putIfAbsent(slot.dayOfWeek(), slot.startTime());
        }
        return map;
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
