package com.crm.service;

import com.crm.dto.request.TeacherRequest;
import com.crm.dto.response.PageResponse;
import com.crm.dto.response.TeacherResponse;
import com.crm.entity.Teacher;
import com.crm.entity.User;
import com.crm.entity.enums.GroupStatus;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.GroupRepository;
import com.crm.repository.TeacherRepository;
import com.crm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TeacherService {

    private final TeacherRepository teacherRepository;
    private final UserRepository userRepository;
    private final GroupRepository groupRepository;

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
