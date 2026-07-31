package com.crm.service;

import com.crm.dto.request.AttendanceRequest;
import com.crm.dto.response.AttendanceResponse;
import com.crm.dto.response.MissingAttendanceResponse;
import com.crm.dto.response.TeacherMissingAttendanceResponse;
import com.crm.entity.*;
import com.crm.entity.enums.AttendanceStatus;
import com.crm.exception.BadRequestException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final int DEFAULT_MISSING_DAYS = 30;

    private final AttendanceRepository attendanceRepository;
    private final StudentRepository studentRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final GroupRepository groupRepository;
    private final GroupScheduleDayRepository groupScheduleDayRepository;
    private final TimetableRepository timetableRepository;
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

        LocalDate date = request.getDate();
        assertGroupHasLessonOnDate(group.getId(), date);

        User marker = teacherAccessService.getCurrentUserOrThrow();

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

    /** Dars bo'lmagan kunga davomat kiritishni bloklaydi (admin uchun ham). */
    void assertGroupHasLessonOnDate(Long groupId, LocalDate date) {
        if (date == null) {
            throw new BadRequestException("Sana majburiy");
        }
        String day = date.getDayOfWeek().name();
        if (!hasLessonOnDayOfWeek(groupId, day)) {
            throw new BadRequestException(
                "Bu kunda guruhda dars yo'q (" + dayToUzbek(day) + ")");
        }
    }

    boolean hasLessonOnDayOfWeek(Long groupId, String dayOfWeek) {
        if (dayOfWeek == null || dayOfWeek.isBlank()) {
            return false;
        }
        String day = dayOfWeek.trim().toUpperCase(Locale.ROOT);
        if (groupScheduleDayRepository.existsByGroup_IdAndDayOfWeekIgnoreCase(groupId, day)) {
            return true;
        }
        return timetableRepository.existsByGroup_IdAndDayOfWeek(groupId, day);
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

    /** dayOfWeek → startTime (birinchi topilgan) */
    private Map<String, String> loadLessonStartTimes(Long groupId) {
        Map<String, String> map = new LinkedHashMap<>();
        for (GroupScheduleDay day : groupScheduleDayRepository.findByGroup_IdOrderByDayOfWeekAsc(groupId)) {
            if (day.getDayOfWeek() == null || day.getDayOfWeek().isBlank()) {
                continue;
            }
            String key = day.getDayOfWeek().trim().toUpperCase(Locale.ROOT);
            map.putIfAbsent(key, normalizeTime(day.getStartTime()));
        }
        if (!map.isEmpty()) {
            return map;
        }
        for (Timetable t : timetableRepository.findByGroupId(groupId)) {
            if (t.getDayOfWeek() == null || t.getDayOfWeek().isBlank()) {
                continue;
            }
            String key = t.getDayOfWeek().trim().toUpperCase(Locale.ROOT);
            String start = t.getStartTime() != null ? t.getStartTime().format(TIME_FMT) : null;
            map.putIfAbsent(key, start);
        }
        return map;
    }

    private static String normalizeTime(String time) {
        if (time == null || time.isBlank()) {
            return null;
        }
        String t = time.trim();
        if (t.length() >= 5) {
            return t.substring(0, 5);
        }
        return t;
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
