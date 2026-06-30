package com.crm.service;

import com.crm.dto.request.TeacherRequest;
import com.crm.dto.response.PageResponse;
import com.crm.dto.response.TeacherKpiAttendanceDto;
import com.crm.dto.response.TeacherKpiConversionDto;
import com.crm.dto.response.TeacherKpiDto;
import com.crm.dto.response.TeacherKpiGroupDto;
import com.crm.dto.response.TeacherKpiSatisfactionDto;
import com.crm.dto.response.TeacherKpiStudentAttendanceDto;
import com.crm.dto.response.TeacherResponse;
import com.crm.entity.Teacher;
import com.crm.entity.User;
import com.crm.entity.enums.AttendanceStatus;
import com.crm.entity.enums.GroupStatus;
import com.crm.exception.ResourceNotFoundException;
import com.crm.entity.Classroom;
import com.crm.entity.Group;
import com.crm.entity.GroupScheduleDay;
import com.crm.entity.Payroll;
import com.crm.repository.AttendanceRepository;
import com.crm.repository.GroupRepository;
import com.crm.repository.GroupScheduleDayRepository;
import com.crm.repository.PayrollRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.TeacherRepository;
import com.crm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TeacherService {

    private final TeacherRepository teacherRepository;
    private final UserRepository userRepository;
    private final GroupRepository groupRepository;
    private final GroupScheduleDayRepository groupScheduleDayRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final PayrollRepository payrollRepository;
    private final AttendanceRepository attendanceRepository;

    @Transactional(readOnly = true)
    public List<TeacherResponse> getAllTeachers(boolean activeOnly) {
        List<Teacher> teachers = activeOnly
            ? teacherRepository.findByIsActiveTrue()
            : teacherRepository.findAll();
        return teachers.stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public TeacherResponse getTeacherById(Long id) {
        return toResponse(findById(id));
    }

    @Transactional
    public TeacherResponse createTeacher(TeacherRequest request) {
        Teacher teacher = buildFromRequest(new Teacher(), request);
        teacher.setIsActive(true);
        teacher.setTeacherCode("TCH-" + String.format("%03d", teacherRepository.count() + 1));

        if (request.getUserId() != null) {
            User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User", request.getUserId()));
            teacher.setUser(user);
        }

        teacher = teacherRepository.save(teacher);
        assignGroupsToTeacher(teacher, request.getGroupIds());
        return toResponse(findById(teacher.getId()));
    }

    @Transactional
    public TeacherResponse updateTeacher(Long id, TeacherRequest request) {
        Teacher teacher = findById(id);
        buildFromRequest(teacher, request);

        if (request.getUserId() != null) {
            User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User", request.getUserId()));
            teacher.setUser(user);
        }

        teacherRepository.save(teacher);
        assignGroupsToTeacher(teacher, request.getGroupIds());
        return toResponse(findById(id));
    }

    @Transactional
    public void deleteTeacher(Long id) {
        Teacher teacher = findById(id);
        teacher.setIsActive(false);
        teacherRepository.save(teacher);
    }

    @Transactional
    public TeacherResponse updatePhoto(Long id, String photoUrl) {
        Teacher teacher = findById(id);
        teacher.setPhotoUrl(photoUrl);
        return toResponse(teacherRepository.save(teacher));
    }

    @Transactional(readOnly = true)
    public PageResponse<TeacherResponse> searchTeachers(String query, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Teacher> p = teacherRepository.searchTeachers(query, pageable);
        return PageResponse.<TeacherResponse>builder()
            .content(p.getContent().stream().map(this::toResponse).collect(Collectors.toList()))
            .pageNumber(page).pageSize(size)
            .totalElements(p.getTotalElements()).totalPages(p.getTotalPages()).last(p.isLast())
            .build();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getTeacherStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", teacherRepository.count());
        stats.put("active", teacherRepository.countByIsActiveTrue());

        Map<String, Long> byStatus = new LinkedHashMap<>();
        teacherRepository.countByStatus().forEach(row -> byStatus.put((String) row[0], (Long) row[1]));
        stats.put("byStatus", byStatus);

        return stats;
    }

    @Transactional(readOnly = true)
    public byte[] exportTeachersCsv() {
        List<Teacher> teachers = teacherRepository.findAll(Sort.by("createdAt").descending());
        StringBuilder csv = new StringBuilder();
        csv.append("ID,UUID,First Name,Last Name,Phone,Email,Subject,Status,Hire Date,Created At\n");
        for (Teacher t : teachers) {
            csv.append(t.getId()).append(",")
               .append(t.getUuid()).append(",")
               .append(esc(t.getFirstName())).append(",")
               .append(esc(t.getLastName())).append(",")
               .append(esc(t.getPhone())).append(",")
               .append(esc(t.getEmail())).append(",")
               .append(esc(t.getSubjectSpecialization())).append(",")
               .append(esc(t.getStatus())).append(",")
               .append(t.getHireDate()).append(",")
               .append(t.getCreatedAt()).append("\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getTeacherDashboard(String username) {
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new ResourceNotFoundException(
                "User not found with username: " + username));

        Teacher teacher = teacherRepository.findByUserId(user.getId())
            .orElseThrow(() -> new ResourceNotFoundException("Teacher", user.getId()));

        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("teacherId", teacher.getId());
        dashboard.put("teacherName", teacher.getFirstName() + " " + teacher.getLastName());

        List<Group> myGroups = groupRepository.findByTeacher_IdAndStatus(
            teacher.getId(), GroupStatus.ACTIVE);
        dashboard.put("totalGroups", myGroups.size());

        dashboard.put("groups", myGroups.stream().map(g -> {
            Map<String, Object> gm = new LinkedHashMap<>();
            gm.put("id", g.getId());
            gm.put("groupName", g.getGroupName());
            gm.put("courseName", g.getCourse() != null
                ? g.getCourse().getCourseName() : null);
            gm.put("studentCount",
                studentGroupRepository.countByGroup_IdAndIsActiveTrue(g.getId()));
            gm.put("scheduleDays",
                groupScheduleDayRepository.findByGroup_IdOrderByDayOfWeekAsc(g.getId())
                    .stream()
                    .map(d -> {
                        Map<String, Object> dm = new LinkedHashMap<>();
                        dm.put("dayOfWeek", d.getDayOfWeek());
                        dm.put("startTime", d.getStartTime() != null ? d.getStartTime() : "");
                        dm.put("endTime", d.getEndTime() != null ? d.getEndTime() : "");
                        return dm;
                    })
                    .collect(Collectors.toList()));
            return gm;
        }).collect(Collectors.toList()));

        String todayDay = java.time.LocalDate.now().getDayOfWeek().toString();
        List<Map<String, Object>> todayLessons = new ArrayList<>();
        for (Group g : myGroups) {
            for (GroupScheduleDay d : groupScheduleDayRepository
                    .findByGroup_IdOrderByDayOfWeekAsc(g.getId())) {
                if (d.getDayOfWeek() == null
                        || !d.getDayOfWeek().equalsIgnoreCase(todayDay)) {
                    continue;
                }
                Map<String, Object> lesson = new LinkedHashMap<>();
                lesson.put("groupId", g.getId());
                lesson.put("groupName", g.getGroupName());
                lesson.put("startTime", d.getStartTime() != null ? d.getStartTime() : "");
                lesson.put("endTime", d.getEndTime() != null ? d.getEndTime() : "");
                lesson.put("roomNumber", d.getRoom() != null ? d.getRoom().getRoomNumber() : "");
                lesson.put("studentCount",
                    studentGroupRepository.countByGroup_IdAndIsActiveTrue(g.getId()));
                todayLessons.add(lesson);
            }
        }
        todayLessons.sort((a, b) -> String.valueOf(a.get("startTime"))
            .compareTo(String.valueOf(b.get("startTime"))));
        dashboard.put("todayLessons", todayLessons);

        long totalStudents = myGroups.stream()
            .mapToLong(g -> studentGroupRepository.countByGroup_IdAndIsActiveTrue(g.getId()))
            .sum();
        dashboard.put("totalStudents", totalStudents);

        java.time.LocalDate now = java.time.LocalDate.now();
        payrollRepository.findByTeacherIdAndMonthAndYear(
                teacher.getId(), now.getMonthValue(), now.getYear())
            .ifPresent(p -> {
                dashboard.put("salary", p.getNetSalary());
                dashboard.put("salaryStatus", p.getStatus());
            });

        return dashboard;
    }

    @Transactional(readOnly = true)
    public TeacherKpiDto getKpi(Long teacherId, LocalDate from, LocalDate to) {
        Teacher teacher = findById(teacherId);

        TeacherKpiDto dto = new TeacherKpiDto();
        dto.setTeacherId(teacherId);
        dto.setTeacherName(teacher.getFirstName() + " " + teacher.getLastName());

        fillFinancialKpi(dto, teacherId, from, to);

        List<Group> groups = groupRepository.findByTeacherId(teacherId);
        List<TeacherKpiGroupDto> groupDtos = groups.stream().map(g -> {
            TeacherKpiGroupDto gd = new TeacherKpiGroupDto();
            gd.setGroupId(g.getId());
            gd.setGroupName(g.getGroupName());
            long count = studentGroupRepository.countByGroupIdAndIsActiveTrue(g.getId());
            gd.setStudentCount(count);
            fillGroupRoomInfo(g, gd, count);
            return gd;
        }).collect(Collectors.toList());
        dto.setGroups(groupDtos);

        List<Long> groupIds = groups.stream().map(Group::getId).collect(Collectors.toList());

        TeacherKpiConversionDto conv = new TeacherKpiConversionDto();
        if (!groupIds.isEmpty()) {
            long newStudents = studentGroupRepository
                .countByGroupIdsAndJoinDateBetween(groupIds, from, to);
            long paidStudents = studentGroupRepository
                .countPaidByGroupIdsAndJoinDateBetween(groupIds, from, to);
            conv.setNewStudents(newStudents);
            conv.setPaidStudents(paidStudents);
            conv.setConversionRate(newStudents > 0
                ? round1(paidStudents * 100.0 / newStudents) : 0.0);
        }
        dto.setConversion(conv);

        TeacherKpiAttendanceDto att = new TeacherKpiAttendanceDto();
        if (!groupIds.isEmpty()) {
            long planned = countPlannedLessons(groups, from, to);
            long conducted = attendanceRepository
                .countDistinctSessionsByGroupIdsAndDateBetween(groupIds, from, to);
            att.setPlannedLessons(planned);
            att.setConductedLessons(conducted);
            att.setMissedLessons(Math.max(0, planned - conducted));
        }
        att.setPenaltyAmount(BigDecimal.ZERO);
        dto.setTeacherAttendance(att);

        TeacherKpiStudentAttendanceDto sAtt = new TeacherKpiStudentAttendanceDto();
        if (!groupIds.isEmpty()) {
            long present = attendanceRepository.countByGroupIdsAndStatusAndDateBetween(
                groupIds, AttendanceStatus.PRESENT, from, to)
                + attendanceRepository.countByGroupIdsAndStatusAndDateBetween(
                    groupIds, AttendanceStatus.LATE, from, to);
            long absent = attendanceRepository.countByGroupIdsAndStatusAndDateBetween(
                groupIds, AttendanceStatus.ABSENT, from, to);
            long excused = attendanceRepository.countByGroupIdsAndStatusAndDateBetween(
                groupIds, AttendanceStatus.EXCUSED, from, to);
            long total = present + absent + excused;
            if (total > 0) {
                sAtt.setPresentRate(round1(present * 100.0 / total));
                sAtt.setAbsentUnexcusedRate(round1(absent * 100.0 / total));
                sAtt.setAbsentExcusedRate(round1(excused * 100.0 / total));
            }
        }
        dto.setStudentAttendance(sAtt);

        dto.setSatisfaction(new TeacherKpiSatisfactionDto());
        dto.setStudentProgress(0.0);

        return dto;
    }

    private void fillFinancialKpi(TeacherKpiDto dto, Long teacherId, LocalDate from, LocalDate to) {
        BigDecimal balance = BigDecimal.ZERO;
        BigDecimal bonus = BigDecimal.ZERO;
        BigDecimal penalty = BigDecimal.ZERO;

        for (Payroll p : payrollRepository.findByTeacherId(teacherId)) {
            if (!payrollOverlapsRange(p, from, to)) {
                continue;
            }
            if (p.getNetSalary() != null) {
                balance = balance.add(p.getNetSalary());
            }
            if (p.getAllowances() != null) {
                bonus = bonus.add(p.getAllowances());
            }
            if (p.getDeductions() != null) {
                penalty = penalty.add(p.getDeductions());
            }
        }

        dto.setBalance(balance);
        dto.setBonus(bonus);
        dto.setAdvance(BigDecimal.ZERO);
        dto.setPenalty(penalty);
    }

    private static boolean payrollOverlapsRange(Payroll p, LocalDate from, LocalDate to) {
        if (p.getMonth() == null || p.getYear() == null) {
            return false;
        }
        LocalDate periodStart = LocalDate.of(p.getYear(), p.getMonth(), 1);
        LocalDate periodEnd = periodStart.withDayOfMonth(periodStart.lengthOfMonth());
        return !periodEnd.isBefore(from) && !periodStart.isAfter(to);
    }

    private void fillGroupRoomInfo(Group g, TeacherKpiGroupDto gd, long count) {
        Classroom classroom = groupScheduleDayRepository
            .findByGroup_IdOrderByDayOfWeekAsc(g.getId()).stream()
            .map(GroupScheduleDay::getRoom)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);

        if (classroom != null) {
            String roomName = classroom.getRoomNumber() != null
                ? classroom.getRoomNumber() : classroom.getRoomName();
            gd.setRoomName(roomName);
            int cap = classroom.getCapacity() != null ? classroom.getCapacity() : 0;
            gd.setCapacity(cap);
            gd.setFreeSeats(Math.max(0, cap - (int) count));
        } else if (g.getRoom() != null && !g.getRoom().isBlank()) {
            gd.setRoomName(g.getRoom());
            int cap = g.getMaxStudents() != null ? g.getMaxStudents() : 0;
            gd.setCapacity(cap);
            gd.setFreeSeats(Math.max(0, cap - (int) count));
        }
    }

    private long countPlannedLessons(List<Group> groups, LocalDate from, LocalDate to) {
        long planned = 0;
        for (Group g : groups) {
            for (GroupScheduleDay d : groupScheduleDayRepository
                    .findByGroup_IdOrderByDayOfWeekAsc(g.getId())) {
                if (d.getDayOfWeek() == null || d.getDayOfWeek().isBlank()) {
                    continue;
                }
                DayOfWeek dow;
                try {
                    dow = DayOfWeek.valueOf(d.getDayOfWeek().trim().toUpperCase());
                } catch (IllegalArgumentException e) {
                    continue;
                }
                LocalDate cur = from;
                while (!cur.isAfter(to)) {
                    if (cur.getDayOfWeek() == dow) {
                        planned++;
                    }
                    cur = cur.plusDays(1);
                }
            }
        }
        return planned;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    public Teacher findById(Long id) {
        return teacherRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Teacher", id));
    }

    private void assignGroupsToTeacher(Teacher teacher, List<Long> groupIds) {
        if (groupIds == null) {
            return;
        }
        for (Long groupId : groupIds) {
            groupRepository.findById(groupId).ifPresent(group -> {
                group.setTeacher(teacher);
                groupRepository.save(group);
            });
        }
    }

    private Teacher buildFromRequest(Teacher t, TeacherRequest req) {
        t.setFirstName(req.getFirstName());
        t.setLastName(req.getLastName());
        t.setPhone(req.getPhone());
        t.setEmail(req.getEmail());
        t.setSubjectSpecialization(req.getSubjectSpecialization());
        t.setMonthlySalary(req.getMonthlySalary());
        t.setHireDate(req.getHireDate());
        t.setNotes(req.getNotes());
        t.setGender(req.getGender());
        t.setDateOfBirth(req.getDateOfBirth());
        t.setFatherName(req.getFatherName());
        t.setMotherName(req.getMotherName());
        t.setAddress(req.getAddress());
        t.setPermanentAddress(req.getPermanentAddress());
        t.setPassportInfo(req.getPassportInfo());
        t.setQualification(req.getQualification());
        t.setWorkExperience(req.getWorkExperience());
        t.setJoiningDate(req.getJoiningDate());
        if (req.getStatus() != null) {
            t.setStatus(req.getStatus());
        }
        t.setBasicSalary(req.getBasicSalary());
        if (req.getMedicalLeaves() != null) {
            t.setMedicalLeaves(req.getMedicalLeaves());
        }
        if (req.getCasualLeaves() != null) {
            t.setCasualLeaves(req.getCasualLeaves());
        }
        if (req.getMaternityLeaves() != null) {
            t.setMaternityLeaves(req.getMaternityLeaves());
        }
        if (req.getSickLeaves() != null) {
            t.setSickLeaves(req.getSickLeaves());
        }
        if (req.getPhotoUrl() != null) {
            t.setPhotoUrl(req.getPhotoUrl());
        }
        return t;
    }

    private TeacherResponse toResponse(Teacher t) {
        long activeGroups = t.getGroups().stream()
            .filter(g -> g.getStatus() == GroupStatus.ACTIVE)
            .count();
        List<TeacherResponse.GroupSummary> groupSummaries = t.getGroups().stream()
            .map(g -> TeacherResponse.GroupSummary.builder()
                .id(g.getId())
                .groupName(g.getGroupName())
                .courseName(g.getCourse() != null ? g.getCourse().getCourseName() : null)
                .build())
            .collect(Collectors.toList());
        return TeacherResponse.builder()
            .id(t.getId())
            .userId(t.getUser() != null ? t.getUser().getId() : null)
            .uuid(t.getUuid())
            .firstName(t.getFirstName()).lastName(t.getLastName())
            .phone(t.getPhone()).email(t.getEmail())
            .subjectSpecialization(t.getSubjectSpecialization())
            .monthlySalary(t.getMonthlySalary()).hireDate(t.getHireDate())
            .isActive(t.getIsActive()).notes(t.getNotes())
            .activeGroupsCount((int) activeGroups)
            .teacherCode(t.getTeacherCode()).gender(t.getGender())
            .dateOfBirth(t.getDateOfBirth())
            .fatherName(t.getFatherName()).motherName(t.getMotherName())
            .address(t.getAddress()).permanentAddress(t.getPermanentAddress())
            .passportInfo(t.getPassportInfo()).qualification(t.getQualification())
            .workExperience(t.getWorkExperience()).joiningDate(t.getJoiningDate())
            .status(t.getStatus()).basicSalary(t.getBasicSalary())
            .medicalLeaves(t.getMedicalLeaves()).casualLeaves(t.getCasualLeaves())
            .maternityLeaves(t.getMaternityLeaves()).sickLeaves(t.getSickLeaves())
            .photoUrl(t.getPhotoUrl())
            .groups(groupSummaries)
            .createdAt(t.getCreatedAt())
            .build();
    }

    private String esc(String val) {
        if (val == null) {
            return "";
        }
        return "\"" + val.replace("\"", "\"\"") + "\"";
    }
}
