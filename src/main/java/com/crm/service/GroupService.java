package com.crm.service;

import com.crm.dto.request.GroupRequest;
import com.crm.dto.request.StudentGroupRequest;
import com.crm.dto.response.GroupResponse;
import com.crm.dto.response.ScheduleResponse;
import com.crm.dto.response.SuspendedStudentResponse;
import com.crm.entity.Course;
import com.crm.entity.Group;
import com.crm.entity.GroupScheduleDay;
import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.entity.StudentStatusHistory;
import com.crm.entity.Teacher;
import com.crm.entity.Timetable;
import com.crm.entity.Classroom;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.PaymentStatus;
import com.crm.exception.BadRequestException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class GroupService {

    private final GroupRepository groupRepository;
    private final CourseService courseService;
    private final TeacherRepository teacherRepository;
    private final StudentRepository studentRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final GroupScheduleDayRepository groupScheduleDayRepository;
    private final ClassroomRepository classroomRepository;
    private final TimetableRepository timetableRepository;
    private final StudentStatusHistoryRepository studentStatusHistoryRepository;
    private final PaymentScheduleService paymentScheduleService;

    @Transactional(readOnly = true)
    public List<GroupResponse> getAllGroups(GroupStatus status) {
        List<Group> groups = status != null
            ? groupRepository.findByStatus(status)
            : groupRepository.findAll();
        Map<Long, Integer> activeCounts = loadActiveStudentCountsByGroup();
        return groups.stream()
            .map(g -> toResponse(g, false, activeCounts.getOrDefault(g.getId(), 0)))
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public GroupResponse getGroupById(Long id) {
        return toResponse(findById(id), true, null);
    }

    @Transactional(readOnly = true)
    public List<GroupResponse.ScheduleDayResponse> getSchedule(Long groupId) {
        findById(groupId);
        return mapScheduleDays(groupId);
    }

    @Transactional(readOnly = true)
    public List<SuspendedStudentResponse> getSuspendedStudents(Long groupId) {
        findById(groupId);
        return studentGroupRepository.findSuspendedByGroupId(groupId).stream()
            .map(sg -> SuspendedStudentResponse.builder()
                .studentId(sg.getStudent().getId())
                .studentName(sg.getStudent().getFirstName() + " " + sg.getStudent().getLastName())
                .groupId(groupId)
                .groupName(sg.getGroup().getGroupName())
                .suspendedAt(sg.getSuspendedAt())
                .suspensionReason(sg.getSuspensionReason())
                .daysSinceSuspended(sg.getSuspendedAt() != null
                    ? ChronoUnit.DAYS.between(sg.getSuspendedAt().toLocalDate(), LocalDate.now()) : null)
                .build())
            .collect(Collectors.toList());
    }

    @Transactional
    public GroupResponse createGroup(GroupRequest request) {
        Course course = courseService.findById(request.getCourseId());

        Group group = Group.builder()
            .groupName(request.getGroupName())
            .course(course)
            .room(request.getRoom())
            .maxStudents(request.getMaxStudents())
            .startDate(request.getStartDate())
            .endDate(request.getEndDate())
            .status(GroupStatus.ACTIVE)
            .notes(request.getNotes())
            .build();

        if (request.getTeacherId() != null) {
            Teacher teacher = teacherRepository.findById(request.getTeacherId())
                .orElseThrow(() -> new ResourceNotFoundException("Teacher", request.getTeacherId()));
            group.setTeacher(teacher);
        }
        applyClassroom(group, request.getClassroomId());

        Group saved = groupRepository.save(group);

        List<GroupRequest.ScheduleDayRequest> resolved = resolveScheduleDays(request);
        if (resolved != null) {
            saveScheduleDays(saved, resolved);
        }

        return toResponse(groupRepository.findById(saved.getId()).orElse(saved), false, null);
    }

    @Transactional
    public GroupResponse updateGroup(Long id, GroupRequest request) {
        Group group = findById(id);
        Course course = courseService.findById(request.getCourseId());

        group.setGroupName(request.getGroupName());
        group.setCourse(course);
        group.setRoom(request.getRoom());
        group.setMaxStudents(request.getMaxStudents());
        group.setStartDate(request.getStartDate());
        group.setEndDate(request.getEndDate());
        group.setNotes(request.getNotes());

        if (request.getTeacherId() != null) {
            Teacher teacher = teacherRepository.findById(request.getTeacherId())
                .orElseThrow(() -> new ResourceNotFoundException("Teacher", request.getTeacherId()));
            group.setTeacher(teacher);
        } else {
            group.setTeacher(null);
        }
        applyClassroom(group, request.getClassroomId());

        Group saved = groupRepository.save(group);

        if (request.getScheduleDays() != null || request.getSchedules() != null) {
            List<GroupRequest.ScheduleDayRequest> resolved = resolveScheduleDays(request);
            if (resolved != null) {
                saveScheduleDays(saved, resolved);
            }
        }

        return toResponse(saved, false, null);
    }

    /**
     * Avval {@code scheduleDays}, bo‘sh bo‘lsa {@code schedules} (legacy) dan birlashtiriladi.
     */
    private List<GroupRequest.ScheduleDayRequest> resolveScheduleDays(GroupRequest request) {
        List<GroupRequest.ScheduleDayRequest> days = request.getScheduleDays();
        if (days != null && !days.isEmpty()) {
            return days;
        }
        if (days != null) {
            return days;
        }
        if (request.getSchedules() != null) {
            if (request.getSchedules().isEmpty()) {
                return List.of();
            }
            return request.getSchedules().stream()
                .map(this::fromLegacyScheduleEntry)
                .collect(Collectors.toList());
        }
        return null;
    }

    private GroupRequest.ScheduleDayRequest fromLegacyScheduleEntry(GroupRequest.ScheduleRequest s) {
        GroupRequest.ScheduleDayRequest d = new GroupRequest.ScheduleDayRequest();
        if (s == null) {
            return d;
        }
        d.setDayOfWeek(s.getDayOfWeek());
        d.setStartTime(s.getStartTime());
        d.setEndTime(s.getEndTime());
        d.setRoomId(s.getRoomId());
        d.setRoomNumber(s.getRoomNumber());
        return d;
    }

    private void saveScheduleDays(Group group, List<GroupRequest.ScheduleDayRequest> days) {
        if (days == null) {
            return;
        }
        groupScheduleDayRepository.deleteByGroup_Id(group.getId());

        for (GroupRequest.ScheduleDayRequest dayReq : days) {
            if (dayReq.getDayOfWeek() == null || dayReq.getDayOfWeek().isBlank()) {
                continue;
            }

            Classroom room = null;
            if (dayReq.getRoomId() != null) {
                room = classroomRepository
                    .findById(dayReq.getRoomId()).orElse(null);
            } else if (dayReq.getRoomNumber() != null && !dayReq.getRoomNumber().isBlank()) {
                room = classroomRepository
                    .findByRoomNumberIgnoreCase(dayReq.getRoomNumber().trim())
                    .orElse(null);
            }
            if (room == null && group.getClassroom() != null) {
                room = group.getClassroom();
            }
            
            // Validate conflict BEFORE saving
            if (room != null && dayReq.getStartTime() != null && !dayReq.getStartTime().isBlank()
                    && dayReq.getEndTime() != null && !dayReq.getEndTime().isBlank()) {
                validateRoomConflict(group, 
                    dayReq.getDayOfWeek().trim(),
                    dayReq.getStartTime(),
                    dayReq.getEndTime(),
                    room);
            }

            GroupScheduleDay scheduleDay = new GroupScheduleDay();
            scheduleDay.setGroup(group);
            scheduleDay.setDayOfWeek(dayReq.getDayOfWeek().trim());
            scheduleDay.setStartTime(dayReq.getStartTime());
            scheduleDay.setEndTime(dayReq.getEndTime());
            scheduleDay.setRoom(room);

            groupScheduleDayRepository.save(scheduleDay);
        }

        syncTimetableFromScheduleDays(group, days);
    }

    private void validateRoomConflict(Group currentGroup,
            String dayOfWeek, String startTime, String endTime,
            Classroom room) {
        
        if (room == null) return;
        
        List<GroupScheduleDay> conflicts = 
            groupScheduleDayRepository.findByRoomAndDayOfWeek(
                room, dayOfWeek);
        
        for (GroupScheduleDay existing : conflicts) {
            if (existing.getGroup().getId()
                    .equals(currentGroup.getId())) continue;
            
            LocalTime newStart = parseScheduleTime(startTime);
            LocalTime newEnd = parseScheduleTime(endTime);
            LocalTime exStart = parseScheduleTime(existing.getStartTime());
            LocalTime exEnd = parseScheduleTime(existing.getEndTime());
            
            if (newStart == null || newEnd == null || exStart == null || exEnd == null) {
                continue;
            }
            
            boolean overlaps = newStart.isBefore(exEnd) && 
                               newEnd.isAfter(exStart);
            
            if (overlaps) {
                throw new BadRequestException(
                    String.format(
                        "Xona %s da %s kuni %s-%s vaqtida " +
                        "allaqachon '%s' guruhi dars o'tmoqda! " +
                        "Boshqa xona yoki vaqt tanlang.",
                        room.getRoomNumber(),
                        dayToUzbek(dayOfWeek),
                        exStart, exEnd,
                        existing.getGroup().getGroupName()
                    )
                );
            }
        }
    }

    private String dayToUzbek(String day) {
        return switch (day.toUpperCase()) {
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

    /**
     * Rebuilds {@link Timetable} rows from {@code scheduleDays} (delete all for group, then insert).
     */
    private void syncTimetableFromScheduleDays(Group group, List<GroupRequest.ScheduleDayRequest> days) {
        timetableRepository.deleteByGroup_Id(group.getId());

        Group persisted = groupRepository.findById(group.getId())
            .orElseThrow(() -> new ResourceNotFoundException("Group", group.getId()));

        if (days == null || days.isEmpty()) {
            log.debug("Timetable cleared for group id={}", group.getId());
            return;
        }

        for (GroupRequest.ScheduleDayRequest dayReq : days) {
            if (dayReq.getDayOfWeek() == null || dayReq.getDayOfWeek().isBlank()) {
                continue;
            }

            LocalTime start = parseScheduleTime(dayReq.getStartTime());
            LocalTime end = parseScheduleTime(dayReq.getEndTime());
            if (start == null || end == null) {
                log.warn("Skip timetable row for group {} — invalid times: {} - {}",
                    group.getId(), dayReq.getStartTime(), dayReq.getEndTime());
                continue;
            }

            Timetable tt = Timetable.builder()
                .group(persisted)
                .dayOfWeek(dayReq.getDayOfWeek().trim())
                .startTime(start)
                .endTime(end)
                .teacher(persisted.getTeacher())
                .build();

            if (tt.getTeacher() == null && persisted.getTeacher() != null) {
                tt.setTeacher(persisted.getTeacher());
            }

            if (persisted.getCourse() != null) {
                tt.setSubjectName(persisted.getCourse().getCourseName());
            }

            if (dayReq.getRoomId() != null) {
                classroomRepository.findById(dayReq.getRoomId()).ifPresent(tt::setClassroom);
            } else if (dayReq.getRoomNumber() != null && !dayReq.getRoomNumber().isBlank()) {
                classroomRepository.findByRoomNumberIgnoreCase(dayReq.getRoomNumber().trim())
                    .ifPresent(tt::setClassroom);
            }
            // Fallback: group default classroom
            if (tt.getClassroom() == null && persisted.getClassroom() != null) {
                tt.setClassroom(persisted.getClassroom());
            }

            timetableRepository.save(tt);
        }

        log.info("Timetable auto-created for group: {}", persisted.getGroupName());
    }

    private static LocalTime parseScheduleTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        try {
            return LocalTime.parse(s);
        } catch (DateTimeParseException e) {
            try {
                return LocalTime.parse(s + ":00");
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    @Transactional
    public GroupResponse updateStatus(Long id, String status) {
        Group group = findById(id);
        GroupStatus parsed;
        try {
            parsed = GroupStatus.valueOf(status.trim().toUpperCase());
        } catch (Exception e) {
            throw new BadRequestException(
                "Noto'g'ri status: " + status +
                ". Ruxsat etilgan: FORMING, ACTIVE, COMPLETED, CANCELLED");
        }
        group.setStatus(parsed);
        return toResponse(groupRepository.save(group), false, null);
    }

    @Transactional
    public void deleteGroup(Long id) {
        Group group = findById(id);
        group.setStatus(GroupStatus.CANCELLED);
        groupRepository.save(group);
    }

    @Transactional
    public void addStudentToGroup(StudentGroupRequest request) {
        Student student = studentRepository.findById(request.getStudentId())
            .orElseThrow(() -> new ResourceNotFoundException("Student", request.getStudentId()));
        Group group = findById(request.getGroupId());

        long currentCount = studentGroupRepository.countByGroupIdAndIsActiveTrue(request.getGroupId());
        if (group.getMaxStudents() != null && currentCount >= group.getMaxStudents()) {
            throw new BadRequestException("Guruh to'lgan! Max: " + group.getMaxStudents());
        }

        if (studentGroupRepository.findByStudentIdAndGroupIdAndIsActiveTrue(
                request.getStudentId(), request.getGroupId())
            .isPresent()) {
            throw new BadRequestException("O'quvchi bu guruhda allaqachon ro'yxatda");
        }

        LocalDate joinDate = request.getJoinDate() != null ? request.getJoinDate() : LocalDate.now();
        LocalDate paymentStart = request.getPaymentStartDate() != null
            ? request.getPaymentStartDate() : LocalDate.now();
        boolean isTrial = Boolean.TRUE.equals(request.getIsTrial());

        BigDecimal fee = request.getMonthlyFee() != null
            ? request.getMonthlyFee()
            : (request.getMonthlyPriceOverride() != null
                ? request.getMonthlyPriceOverride()
                : (group.getCourse() != null && group.getCourse().getMonthlyPrice() != null
                    ? group.getCourse().getMonthlyPrice()
                    : BigDecimal.ZERO));

        String initialStatus = isTrial ? "TRIAL" : "PENDING";

        student.setPaymentStartDate(paymentStart);
        student.setMonthlyFee(fee);
        student.setPaymentStatus(isTrial ? PaymentStatus.TRIAL : PaymentStatus.PENDING);
        studentRepository.save(student);

        StudentGroup sg = StudentGroup.builder()
            .student(student)
            .group(group)
            .joinDate(joinDate)
            .paymentStartDate(paymentStart)
            .nextPaymentDate(paymentStart)
            .isTrial(isTrial)
            .isActive(true)
            .discountPercentage(request.getDiscountPercentage())
            .monthlyPriceOverride(fee)
            .notes(request.getNotes())
            .paymentStatus(initialStatus)
            .lessonsAttended(0)
            .build();

        studentGroupRepository.save(sg);
        studentGroupRepository.flush();
        LocalDate studentNext = paymentScheduleService.recalculateForStudent(student);
        log.info("addStudentToGroup student={} sg={} paymentStart={} → student.nextPaymentDate={}",
            student.getId(), sg.getId(), paymentStart, studentNext);
    }

    @Transactional
    public void removeStudentFromGroup(Long studentId, Long groupId) {
        StudentGroup sg = studentGroupRepository.findByStudentIdAndGroupIdAndIsActiveTrue(studentId, groupId)
            .orElseThrow(() -> new ResourceNotFoundException("Student is not in this group"));
        sg.setIsActive(false);
        sg.setLeaveDate(LocalDate.now());
        studentGroupRepository.save(sg);
        paymentScheduleService.clearPaymentSchedule(studentId);
    }

    @Transactional
    public void removeStudentFromGroup(Long groupId, Long studentId,
            String reason, String notes) {
        StudentGroup sg = studentGroupRepository
            .findByStudentIdAndGroupIdAndIsActiveTrue(studentId, groupId)
            .orElseThrow(() -> new ResourceNotFoundException("StudentGroup", studentId));

        sg.setIsActive(false);
        sg.setExitDate(LocalDate.now());
        sg.setLeaveDate(LocalDate.now());
        sg.setExitReason(reason);
        sg.setExitNotes(notes);
        studentGroupRepository.save(sg);

        // Update student status based on reason
        Student student = sg.getStudent();
        String previousStatus = student.getStatus() != null ? student.getStatus().name() : "ACTIVE";
        switch (reason != null ? reason : "OTHER") {
            case "GRADUATED" -> student.setStatus(com.crm.entity.enums.StudentStatus.GRADUATED);
            case "LEFT" -> student.setStatus(com.crm.entity.enums.StudentStatus.LEFT);
            case "TRANSFERRED" -> {
                // Keep ACTIVE - just moving groups
            }
            case "SUSPENDED" -> student.setStatus(com.crm.entity.enums.StudentStatus.SUSPENDED);
        }
        studentRepository.save(student);

        // Save to student history
        StudentStatusHistory history = new StudentStatusHistory();
        history.setStudent(student);
        history.setFromStatus(previousStatus);
        history.setToStatus(student.getStatus() != null ? student.getStatus().name() : previousStatus);
        history.setReason(reason);
        history.setNotes(notes);
        history.setChangedAt(LocalDateTime.now());
        studentStatusHistoryRepository.save(history);

        paymentScheduleService.clearPaymentSchedule(student.getId());
    }

    public Group findById(Long id) {
        return groupRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Group", id));
    }

    private List<GroupResponse.ScheduleDayResponse> mapScheduleDays(Long groupId) {
        return mapToScheduleDayResponses(
            groupScheduleDayRepository.findByGroup_IdOrderByDayOfWeekAsc(groupId));
    }

    private List<GroupResponse.ScheduleDayResponse> mapToScheduleDayResponses(List<GroupScheduleDay> savedDays) {
        return savedDays.stream()
            .map(d -> GroupResponse.ScheduleDayResponse.builder()
                .id(d.getId())
                .dayOfWeek(d.getDayOfWeek())
                .startTime(d.getStartTime() != null ? d.getStartTime() : null)
                .endTime(d.getEndTime() != null ? d.getEndTime() : null)
                .roomNumber(d.getRoom() != null ? d.getRoom().getRoomNumber() : null)
                .roomId(d.getRoom() != null ? d.getRoom().getId() : null)
                .build())
            .collect(Collectors.toList());
    }

    private List<ScheduleResponse> mapToScheduleResponses(List<GroupScheduleDay> savedDays) {
        return savedDays.stream()
            .map(d -> ScheduleResponse.builder()
                .id(d.getId())
                .dayOfWeek(parseDayOfWeekSafe(d.getDayOfWeek()))
                .startTime(parseScheduleTime(d.getStartTime()))
                .endTime(parseScheduleTime(d.getEndTime()))
                .roomId(d.getRoom() != null ? d.getRoom().getId() : null)
                .roomNumber(d.getRoom() != null ? d.getRoom().getRoomNumber() : null)
                .build())
            .collect(Collectors.toList());
    }

    private static DayOfWeek parseDayOfWeekSafe(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return DayOfWeek.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Map<Long, Integer> loadActiveStudentCountsByGroup() {
        Map<Long, Integer> counts = new HashMap<>();
        for (Object[] row : studentGroupRepository.countActiveStudentsGroupedByGroupId()) {
            Long groupId = row[0] instanceof Number n ? n.longValue() : null;
            int count = row[1] instanceof Number n ? n.intValue() : 0;
            if (groupId != null) {
                counts.put(groupId, count);
            }
        }
        return counts;
    }

    private GroupResponse toResponse(Group g, boolean includeMembers, Integer currentStudentsOverride) {
        List<GroupScheduleDay> savedDays =
            groupScheduleDayRepository.findByGroup_IdOrderByDayOfWeekAsc(g.getId());
        List<ScheduleResponse> schedules = mapToScheduleResponses(savedDays);
        List<GroupResponse.ScheduleDayResponse> scheduleDays = mapToScheduleDayResponses(savedDays);

        List<GroupResponse.StudentSummary> members = null;
        int currentForResponse;
        if (includeMembers) {
            List<StudentGroup> activeInGroup = studentGroupRepository.findActiveByGroupId(g.getId());
            currentForResponse = activeInGroup.size();
            members = activeInGroup.stream()
                .map(sg -> {
                    Student st = sg.getStudent();
                    String name = st.getFirstName() + " " + st.getLastName();
                    LocalDate joined = sg.getJoinDate();
                    BigDecimal fee = st.getMonthlyFee() != null
                        ? st.getMonthlyFee()
                        : PaymentScheduleService.resolveMonthlyFee(sg);
                    LocalDate payStart = st.getPaymentStartDate() != null
                        ? st.getPaymentStartDate()
                        : sg.getPaymentStartDate();
                    LocalDate nextDue = st.getNextPaymentDate() != null
                        ? st.getNextPaymentDate()
                        : sg.getNextPaymentDate();
                    String payStatus = st.getPaymentStatus() != null
                        ? st.getPaymentStatus().name()
                        : sg.getPaymentStatus();
                    if (payStatus == null) {
                        payStatus = PaymentStatus.PENDING.name();
                    }
                    return GroupResponse.StudentSummary.builder()
                        .studentId(st.getId())
                        .fullName(name)
                        .studentName(name)
                        .phone(st.getPhone())
                        .joinedAt(joined)
                        .joinDate(joined)
                        .paymentStatus(payStatus)
                        .paymentStartDate(payStart)
                        .nextPaymentDate(nextDue)
                        .monthlyFee(fee)
                        .status(st.getStatus() != null ? st.getStatus().name() : null)
                        .build();
                })
                .collect(Collectors.toList());
        } else if (currentStudentsOverride != null) {
            currentForResponse = currentStudentsOverride;
        } else {
            currentForResponse = (int) studentGroupRepository.countByGroupIdAndIsActiveTrue(g.getId());
        }

        BigDecimal coursePrice = g.getCourse() != null && g.getCourse().getMonthlyPrice() != null
            ? g.getCourse().getMonthlyPrice()
            : BigDecimal.ZERO;
        // Group o'z monthlyFee maydoniga ega emas — narx Course.monthlyPrice da
        BigDecimal monthlyFee = coursePrice;

        if (coursePrice.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Group id={} name='{}' coursePrice=0 — kurs narxi to'ldirilmagan",
                g.getId(), g.getGroupName());
        }

        return GroupResponse.builder()
            .id(g.getId())
            .uuid(g.getUuid())
            .groupName(g.getGroupName())
            .courseId(g.getCourse().getId())
            .courseName(g.getCourse().getCourseName())
            .teacherId(g.getTeacher() != null ? g.getTeacher().getId() : null)
            .teacherName(g.getTeacher() != null
                ? g.getTeacher().getFirstName() + " " + g.getTeacher().getLastName() : null)
            .room(g.getRoom())
            .classroomId(g.getClassroom() != null ? g.getClassroom().getId() : null)
            .classroomName(g.getClassroom() != null ? classroomDisplayName(g.getClassroom()) : null)
            .monthlyFee(monthlyFee)
            .coursePrice(coursePrice)
            .maxStudents(g.getMaxStudents())
            .currentStudents(currentForResponse)
            .startDate(g.getStartDate())
            .endDate(g.getEndDate())
            .notes(g.getNotes())
            .status(g.getStatus())
            .schedules(schedules)
            .scheduleDays(scheduleDays)
            .studentGroups(members)
            .students(members)
            .createdAt(g.getCreatedAt())
            .build();
    }

    private void applyClassroom(Group group, Long classroomId) {
        if (classroomId != null) {
            Classroom classroom = classroomRepository.findById(classroomId)
                .orElseThrow(() -> new ResourceNotFoundException("Classroom", classroomId));
            group.setClassroom(classroom);
            if (group.getRoom() == null || group.getRoom().isBlank()) {
                group.setRoom(classroomDisplayName(classroom));
            }
        } else {
            group.setClassroom(null);
        }
    }

    private static String classroomDisplayName(Classroom c) {
        if (c.getRoomNumber() != null && !c.getRoomNumber().isBlank()) {
            return c.getRoomNumber();
        }
        return c.getRoomName();
    }

    @Transactional
    public Map<String, Integer> linkTimetableRoomsFromGroups() {
        List<Timetable> orphans = timetableRepository.findAllWithNullClassroom();
        int updated = 0;
        int skipped = 0;
        for (Timetable t : orphans) {
            Group g = t.getGroup();
            if (g != null && g.getClassroom() != null) {
                t.setClassroom(g.getClassroom());
                timetableRepository.save(t);
                updated++;
            } else {
                skipped++;
            }
        }
        return Map.of("updated", updated, "skipped", skipped);
    }
}
