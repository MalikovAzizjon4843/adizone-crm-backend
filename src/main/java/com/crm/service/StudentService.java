package com.crm.service;

import com.crm.dto.request.StudentRequest;
import com.crm.dto.response.*;
import com.crm.entity.Student;
import com.crm.entity.enums.StudentStatus;
import com.crm.exception.DuplicateResourceException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.StudentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StudentService {

    private final StudentRepository studentRepository;

    @Transactional(readOnly = true)
    public PageResponse<StudentResponse> getAllStudents(int page, int size, String search, StudentStatus status) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Student> studentPage;

        if (search != null && !search.isBlank()) {
            studentPage = studentRepository.searchStudents(search, pageable);
        } else if (status != null) {
            studentPage = studentRepository.findByStatus(status, pageable);
        } else {
            studentPage = studentRepository.findAll(pageable);
        }

        List<StudentResponse> content = studentPage.getContent()
            .stream().map(this::toResponse).collect(Collectors.toList());

        return PageResponse.<StudentResponse>builder()
            .content(content)
            .pageNumber(page)
            .pageSize(size)
            .totalElements(studentPage.getTotalElements())
            .totalPages(studentPage.getTotalPages())
            .last(studentPage.isLast())
            .build();
    }

    @Transactional(readOnly = true)
    public StudentDetailResponse getStudentById(Long id) {
        Student student = findById(id);
        return toDetailResponse(student);
    }

    @Transactional
    public StudentResponse createStudent(StudentRequest request) {
        if (studentRepository.findByPhone(request.getPhone()).isPresent()) {
            throw new DuplicateResourceException("Student with phone already exists: " + request.getPhone());
        }

        Student student = Student.builder()
            .firstName(request.getFirstName())
            .lastName(request.getLastName())
            .phone(request.getPhone())
            .parentPhone(request.getParentPhone())
            .birthDate(request.getBirthDate())
            .marketingSource(request.getMarketingSource())
            .status(request.getStatus())
            .notes(request.getNotes())
            .address(request.getAddress())
            .build();

        if (request.getReferralStudentId() != null) {
            Student referral = findById(request.getReferralStudentId());
            student.setReferralStudent(referral);
        }

        return toResponse(studentRepository.save(student));
    }

    @Transactional
    public StudentResponse updateStudent(Long id, StudentRequest request) {
        Student student = findById(id);

        studentRepository.findByPhone(request.getPhone())
            .filter(s -> !s.getId().equals(id))
            .ifPresent(s -> { throw new DuplicateResourceException("Phone already used by another student"); });

        student.setFirstName(request.getFirstName());
        student.setLastName(request.getLastName());
        student.setPhone(request.getPhone());
        student.setParentPhone(request.getParentPhone());
        student.setBirthDate(request.getBirthDate());
        student.setMarketingSource(request.getMarketingSource());
        student.setStatus(request.getStatus());
        student.setNotes(request.getNotes());
        student.setAddress(request.getAddress());

        return toResponse(studentRepository.save(student));
    }

    @Transactional
    public void deleteStudent(Long id) {
        Student student = findById(id);
        student.setStatus(StudentStatus.LEFT);
        studentRepository.save(student);
    }

    public Student findById(Long id) {
        return studentRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Student", id));
    }

    private StudentResponse toResponse(Student s) {
        return StudentResponse.builder()
            .id(s.getId())
            .uuid(s.getUuid())
            .firstName(s.getFirstName())
            .lastName(s.getLastName())
            .phone(s.getPhone())
            .parentPhone(s.getParentPhone())
            .birthDate(s.getBirthDate())
            .marketingSource(s.getMarketingSource())
            .status(s.getStatus())
            .notes(s.getNotes())
            .address(s.getAddress())
            .createdAt(s.getCreatedAt())
            .build();
    }

    private StudentDetailResponse toDetailResponse(Student s) {
        List<StudentGroupResponse> groups = s.getStudentGroups().stream()
            .filter(sg -> sg.getIsActive())
            .map(sg -> StudentGroupResponse.builder()
                .id(sg.getId())
                .groupId(sg.getGroup().getId())
                .groupName(sg.getGroup().getGroupName())
                .courseName(sg.getGroup().getCourse().getCourseName())
                .teacherName(sg.getGroup().getTeacher() != null
                    ? sg.getGroup().getTeacher().getFirstName() + " " + sg.getGroup().getTeacher().getLastName() : null)
                .joinDate(sg.getJoinDate())
                .nextPaymentDate(sg.getNextPaymentDate())
                .monthlyPrice(sg.getMonthlyPriceOverride() != null
                    ? sg.getMonthlyPriceOverride() : sg.getGroup().getCourse().getMonthlyPrice())
                .isActive(sg.getIsActive())
                .build())
            .collect(Collectors.toList());

        List<PaymentResponse> payments = s.getPayments().stream()
            .limit(10)
            .map(p -> PaymentResponse.builder()
                .id(p.getId())
                .uuid(p.getUuid())
                .studentId(s.getId())
                .studentName(s.getFirstName() + " " + s.getLastName())
                .amount(p.getAmount())
                .paymentDate(p.getPaymentDate())
                .paymentMethod(p.getPaymentMethod())
                .status(p.getStatus())
                .periodFrom(p.getPeriodFrom())
                .periodTo(p.getPeriodTo())
                .build())
            .collect(Collectors.toList());

        return StudentDetailResponse.builder()
            .id(s.getId())
            .uuid(s.getUuid())
            .firstName(s.getFirstName())
            .lastName(s.getLastName())
            .phone(s.getPhone())
            .parentPhone(s.getParentPhone())
            .birthDate(s.getBirthDate())
            .marketingSource(s.getMarketingSource())
            .status(s.getStatus())
            .notes(s.getNotes())
            .address(s.getAddress())
            .activeGroups(groups)
            .recentPayments(payments)
            .createdAt(s.getCreatedAt())
            .build();
    }
}
