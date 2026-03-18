package com.crm.service;

import com.crm.dto.request.GroupRequest;
import com.crm.dto.request.StudentGroupRequest;
import com.crm.dto.response.GroupResponse;
import com.crm.dto.response.ScheduleResponse;
import com.crm.dto.response.StudentGroupResponse;
import com.crm.entity.*;
import com.crm.entity.enums.GroupStatus;
import com.crm.exception.BadRequestException;
import com.crm.exception.DuplicateResourceException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GroupService {

    private final GroupRepository groupRepository;
    private final CourseService courseService;
    private final TeacherRepository teacherRepository;
    private final StudentRepository studentRepository;
    private final StudentGroupRepository studentGroupRepository;

    @Transactional(readOnly = true)
    public List<GroupResponse> getAllGroups(GroupStatus status) {
        List<Group> groups = status != null
            ? groupRepository.findByStatus(status)
            : groupRepository.findAll();
        return groups.stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public GroupResponse getGroupById(Long id) {
        return toResponse(findById(id));
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

        if (request.getSchedules() != null) {
            List<GroupSchedule> schedules = request.getSchedules().stream()
                .map(s -> GroupSchedule.builder()
                    .group(saved)
                    .dayOfWeek(s.getDayOfWeek())
                    .startTime(s.getStartTime())
                    .endTime(s.getEndTime())
                    .build())
                .collect(Collectors.toList());
            saved.setSchedules(schedules);
            groupRepository.save(saved);
        }

        return toResponse(saved);
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

        return toResponse(groupRepository.save(group));
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
            .build();

        StudentGroup saved = studentGroupRepository.save(sg);

        return StudentGroupResponse.builder()
            .id(saved.getId())
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

    private GroupResponse toResponse(Group g) {
        List<ScheduleResponse> schedules = g.getSchedules().stream()
            .map(s -> ScheduleResponse.builder()
                .id(s.getId())
                .dayOfWeek(s.getDayOfWeek())
                .startTime(s.getStartTime())
                .endTime(s.getEndTime())
                .build())
            .collect(Collectors.toList());

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
            .status(g.getStatus())
            .schedules(schedules)
            .createdAt(g.getCreatedAt())
            .build();
    }
}
