package com.crm.service;

import com.crm.dto.request.GroupRequest;
import com.crm.dto.request.StudentGroupRequest;
import com.crm.dto.response.GroupResponse;
import com.crm.dto.response.ScheduleResponse;
import com.crm.dto.response.StudentGroupResponse;
import com.crm.dto.response.SuspendedStudentResponse;
import com.crm.entity.Course;
import com.crm.entity.Group;
import com.crm.entity.GroupScheduleDay;
import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.entity.Teacher;
import com.crm.entity.Timetable;
import com.crm.entity.enums.GroupStatus;
import com.crm.exception.BadRequestException;
import com.crm.exception.DuplicateResourceException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
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

    @Transactional(readOnly = true)
    public List<GroupResponse> getAllGroups(GroupStatus status) {
        List<Group> groups = status != null
            ? groupRepository.findByStatus(status)
            : groupRepository.findAll();
        return groups.stream().map(g -> toResponse(g, false)).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public GroupResponse getGroupById(Long id) {
        return toResponse(findById(id), true);
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

        Group saved = groupRepository.save(group);

        List<GroupRequest.ScheduleDayRequest> resolved = resolveScheduleDays(request);
        if (resolved != null) {
            saveScheduleDays(saved, resolved);
        }

        return toResponse(groupRepository.findById(saved.getId()).orElse(saved), false);
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

        Group saved = groupRepository.save(group);

        if (request.getScheduleDays() != null || request.getSchedules() != null) {
            List<GroupRequest.ScheduleDayRequest> resolved = resolveScheduleDays(request);
            if (resolved != null) {
                saveScheduleDays(saved, resolved);
            }
        }

        return toResponse(saved, false);
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

        for (GroupRequest.ScheduleDayRequest day : days) {
            if (day.getDayOfWeek() == null || day.getDayOfWeek().isBlank()) {
                continue;
            }

            if (day.getRoomId() != null
                && day.getStartTime() != null && !day.getStartTime().isBlank()
                && day.getEndTime() != null && !day.getEndTime().isBlank()) {
                List<GroupScheduleDay> conflicts = groupScheduleDayRepository.findConflicts(
                    day.getRoomId(),
                    day.getDayOfWeek().trim(),
                    day.getStartTime(),
                    day.getEndTime(),
                    group.getId());
                if (!conflicts.isEmpty()) {
                    throw new BadRequestException(
                        day.getRoomId() + " xonada " + day.getDayOfWeek() + " kuni "
                            + day.getStartTime() + "-" + day.getEndTime()
                            + " vaqtida boshqa guruh dars o'tmoqda!");
                }
            }

            GroupScheduleDay scheduleDay = new GroupScheduleDay();
            scheduleDay.setGroup(group);
            scheduleDay.setDayOfWeek(day.getDayOfWeek().trim());
            scheduleDay.setStartTime(day.getStartTime());
            scheduleDay.setEndTime(day.getEndTime());

            if (day.getRoomId() != null) {
                classroomRepository.findById(day.getRoomId()).ifPresent(scheduleDay::setRoom);
            } else if (day.getRoomNumber() != null && !day.getRoomNumber().isBlank()) {
                classroomRepository.findByRoomNumberIgnoreCase(day.getRoomNumber().trim())
                    .ifPresent(scheduleDay::setRoom);
            }

            groupScheduleDayRepository.save(scheduleDay);
        }

        syncTimetableFromScheduleDays(group, days);
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
    public void deleteGroup(Long id) {
        Group group = findById(id);
        group.setStatus(GroupStatus.CANCELLED);
        groupRepository.save(group);
    }

    @Transactional
    public StudentGroupResponse addStudentToGroup(StudentGroupRequest request) {
        Student student = studentRepository.findById(request.getStudentId())
            .orElseThrow(() -> new ResourceNotFoundException("Student", request.getStudentId()));
        Group group = findById(request.getGroupId());

        if (studentGroupRepository.existsByStudentIdAndGroupIdAndIsActiveTrue(
                request.getStudentId(), request.getGroupId())) {
            throw new DuplicateResourceException("Student is already in this group");
        }

        if (group.getCurrentStudents() >= group.getMaxStudents()) {
            throw new BadRequestException("Group is full (max: " + group.getMaxStudents() + ")");
        }

        LocalDate joinDate = request.getJoinDate() != null ? request.getJoinDate() : LocalDate.now();

        StudentGroup sg = StudentGroup.builder()
            .student(student)
            .group(group)
            .joinDate(joinDate)
            .paymentStartDate(joinDate)
            .nextPaymentDate(joinDate.plusDays(30))
            .isActive(true)
            .discountPercentage(request.getDiscountPercentage())
            .monthlyPriceOverride(request.getMonthlyPriceOverride())
            .notes(request.getNotes())
            .paymentStatus("TRIAL")
            .lessonsAttended(0)
            .build();

        StudentGroup saved = studentGroupRepository.save(sg);

        return StudentGroupResponse.builder()
            .id(saved.getId())
            .studentId(student.getId())
            .studentName(student.getFirstName() + " " + student.getLastName())
            .groupId(group.getId())
            .groupName(group.getGroupName())
            .courseName(group.getCourse().getCourseName())
            .teacherName(group.getTeacher() != null
                ? group.getTeacher().getFirstName() + " " + group.getTeacher().getLastName() : null)
            .joinDate(saved.getJoinDate())
            .nextPaymentDate(saved.getNextPaymentDate())
            .isActive(saved.getIsActive())
            .build();
    }

    @Transactional
    public void removeStudentFromGroup(Long studentId, Long groupId) {
        StudentGroup sg = studentGroupRepository.findByStudentIdAndGroupIdAndIsActiveTrue(studentId, groupId)
            .orElseThrow(() -> new ResourceNotFoundException("Student is not in this group"));
        sg.setIsActive(false);
        sg.setLeaveDate(LocalDate.now());
        studentGroupRepository.save(sg);
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

    private GroupResponse toResponse(Group g, boolean includeMembers) {
        List<GroupScheduleDay> savedDays =
            groupScheduleDayRepository.findByGroup_IdOrderByDayOfWeekAsc(g.getId());
        List<ScheduleResponse> schedules = mapToScheduleResponses(savedDays);
        List<GroupResponse.ScheduleDayResponse> scheduleDays = mapToScheduleDayResponses(savedDays);

        List<StudentGroupResponse> members = null;
        if (includeMembers && g.getStudentGroups() != null) {
            members = g.getStudentGroups().stream()
                .filter(sg -> Boolean.TRUE.equals(sg.getIsActive()))
                .map(sg -> {
                    Student st = sg.getStudent();
                    return StudentGroupResponse.builder()
                        .id(sg.getId())
                        .studentId(st.getId())
                        .studentName(st.getFirstName() + " " + st.getLastName())
                        .groupId(g.getId())
                        .groupName(g.getGroupName())
                        .courseName(g.getCourse().getCourseName())
                        .teacherName(g.getTeacher() != null
                            ? g.getTeacher().getFirstName() + " " + g.getTeacher().getLastName() : null)
                        .joinDate(sg.getJoinDate())
                        .nextPaymentDate(sg.getNextPaymentDate())
                        .monthlyPrice(sg.getMonthlyPriceOverride() != null
                            ? sg.getMonthlyPriceOverride() : g.getCourse().getMonthlyPrice())
                        .isActive(sg.getIsActive())
                        .build();
                })
                .collect(Collectors.toList());
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
            .maxStudents(g.getMaxStudents())
            .currentStudents(g.getCurrentStudents())
            .startDate(g.getStartDate())
            .endDate(g.getEndDate())
            .notes(g.getNotes())
            .status(g.getStatus())
            .schedules(schedules)
            .scheduleDays(scheduleDays)
            .studentGroups(members)
            .createdAt(g.getCreatedAt())
            .build();
    }
}
