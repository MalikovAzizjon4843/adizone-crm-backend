package com.crm.service;

import com.crm.dto.request.TeacherRequest;
import com.crm.dto.response.TeacherResponse;
import com.crm.entity.Teacher;
import com.crm.entity.User;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.TeacherRepository;
import com.crm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TeacherService {

    private final TeacherRepository teacherRepository;
    private final UserRepository userRepository;

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
        Teacher teacher = Teacher.builder()
            .firstName(request.getFirstName())
            .lastName(request.getLastName())
            .phone(request.getPhone())
            .email(request.getEmail())
            .subjectSpecialization(request.getSubjectSpecialization())
            .monthlySalary(request.getMonthlySalary())
            .hireDate(request.getHireDate())
            .notes(request.getNotes())
            .isActive(true)
            .build();

        if (request.getUserId() != null) {
            User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User", request.getUserId()));
            teacher.setUser(user);
        }

        return toResponse(teacherRepository.save(teacher));
    }

    @Transactional
    public TeacherResponse updateTeacher(Long id, TeacherRequest request) {
        Teacher teacher = findById(id);
        teacher.setFirstName(request.getFirstName());
        teacher.setLastName(request.getLastName());
        teacher.setPhone(request.getPhone());
        teacher.setEmail(request.getEmail());
        teacher.setSubjectSpecialization(request.getSubjectSpecialization());
        teacher.setMonthlySalary(request.getMonthlySalary());
        teacher.setHireDate(request.getHireDate());
        teacher.setNotes(request.getNotes());
        return toResponse(teacherRepository.save(teacher));
    }

    @Transactional
    public void deleteTeacher(Long id) {
        Teacher teacher = findById(id);
        teacher.setIsActive(false);
        teacherRepository.save(teacher);
    }

    public Teacher findById(Long id) {
        return teacherRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Teacher", id));
    }

    private TeacherResponse toResponse(Teacher t) {
        long activeGroups = t.getGroups().stream()
            .filter(g -> g.getStatus().name().equals("ACTIVE")).count();
        return TeacherResponse.builder()
            .id(t.getId())
            .uuid(t.getUuid())
            .firstName(t.getFirstName())
            .lastName(t.getLastName())
            .phone(t.getPhone())
            .email(t.getEmail())
            .subjectSpecialization(t.getSubjectSpecialization())
            .monthlySalary(t.getMonthlySalary())
            .hireDate(t.getHireDate())
            .isActive(t.getIsActive())
            .notes(t.getNotes())
            .activeGroupsCount((int) activeGroups)
            .createdAt(t.getCreatedAt())
            .build();
    }
}
