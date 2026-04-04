package com.crm.service;

import com.crm.dto.request.StudentRequest;
import com.crm.dto.response.*;
import com.crm.entity.Payment;
import com.crm.entity.Student;
import com.crm.entity.enums.StudentStatus;
import com.crm.exception.DuplicateResourceException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.StudentParentRepository;
import com.crm.repository.StudentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StudentService {

    private final StudentRepository studentRepository;
    private final StudentParentRepository studentParentRepository;

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

        Student student = buildFromRequest(new Student(), request);

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

        buildFromRequest(student, request);

        if (request.getReferralStudentId() != null) {
            Student referral = findById(request.getReferralStudentId());
            student.setReferralStudent(referral);
        } else {
            student.setReferralStudent(null);
        }

        return toResponse(studentRepository.save(student));
    }

    @Transactional
    public void deleteStudent(Long id) {
        Student student = findById(id);
        student.setStatus(StudentStatus.LEFT);
        studentRepository.save(student);
    }

    @Transactional(readOnly = true)
    public PageResponse<StudentResponse> searchStudents(String query, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Student> studentPage = studentRepository.searchStudents(query, pageable);
        List<StudentResponse> content = studentPage.getContent()
            .stream().map(this::toResponse).collect(Collectors.toList());
        return PageResponse.<StudentResponse>builder()
            .content(content).pageNumber(page).pageSize(size)
            .totalElements(studentPage.getTotalElements())
            .totalPages(studentPage.getTotalPages()).last(studentPage.isLast())
            .build();
    }

    @Transactional
    public StudentResponse updatePhoto(Long id, String photoUrl) {
        Student student = findById(id);
        student.setPhotoUrl(photoUrl);
        return toResponse(studentRepository.save(student));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getStudentStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", studentRepository.count());
        stats.put("active", studentRepository.countByStatus(StudentStatus.ACTIVE));
        stats.put("frozen", studentRepository.countByStatus(StudentStatus.FROZEN));
        stats.put("finished", studentRepository.countByStatus(StudentStatus.FINISHED));
        stats.put("left", studentRepository.countByStatus(StudentStatus.LEFT));

        Map<String, Long> byGender = new LinkedHashMap<>();
        studentRepository.countByGender().forEach(row -> byGender.put((String) row[0], (Long) row[1]));
        stats.put("byGender", byGender);

        Map<String, Long> bySource = new LinkedHashMap<>();
        studentRepository.countByMarketingSourceGrouped()
            .forEach(row -> bySource.put(row[0].toString(), (Long) row[1]));
        stats.put("bySource", bySource);

        return stats;
    }

    @Transactional(readOnly = true)
    public byte[] exportStudentsCsv() {
        List<Student> students = studentRepository.findAll(Sort.by("createdAt").descending());
        StringBuilder csv = new StringBuilder();
        csv.append("ID,UUID,First Name,Last Name,Phone,Admission Number,Gender,Status,Marketing Source,Created At\n");
        for (Student s : students) {
            csv.append(s.getId()).append(",")
               .append(s.getUuid()).append(",")
               .append(esc(s.getFirstName())).append(",")
               .append(esc(s.getLastName())).append(",")
               .append(esc(s.getPhone())).append(",")
               .append(esc(s.getAdmissionNumber())).append(",")
               .append(esc(s.getGender())).append(",")
               .append(s.getStatus()).append(",")
               .append(s.getMarketingSource()).append(",")
               .append(s.getCreatedAt()).append("\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    public Student findById(Long id) {
        return studentRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Student", id));
    }

    private Student buildFromRequest(Student s, StudentRequest req) {
        s.setFirstName(req.getFirstName());
        s.setLastName(req.getLastName());
        s.setPhone(req.getPhone());
        s.setParentPhone(req.getParentPhone());
        s.setBirthDate(req.getBirthDate());
        s.setGender(req.getGender());
        s.setMarketingSource(req.getMarketingSource());
        s.setStatus(req.getStatus() != null ? req.getStatus() : StudentStatus.ACTIVE);
        s.setNotes(req.getNotes());
        s.setAddress(req.getAddress());
        s.setPhotoUrl(req.getPhotoUrl());
        s.setAdmissionNumber(req.getAdmissionNumber());
        s.setAdmissionDate(req.getAdmissionDate());
        return s;
    }

    private StudentResponse toResponse(Student s) {
        return StudentResponse.builder()
            .id(s.getId()).uuid(s.getUuid())
            .firstName(s.getFirstName()).lastName(s.getLastName())
            .phone(s.getPhone()).parentPhone(s.getParentPhone())
            .birthDate(s.getBirthDate()).gender(s.getGender())
            .marketingSource(s.getMarketingSource())
            .status(s.getStatus()).notes(s.getNotes())
            .address(s.getAddress()).photoUrl(s.getPhotoUrl())
            .admissionNumber(s.getAdmissionNumber()).admissionDate(s.getAdmissionDate())
            .referralStudentId(s.getReferralStudent() != null ? s.getReferralStudent().getId() : null)
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

        List<StudentDetailResponse.StudentParentInfo> parents = studentParentRepository
            .findByStudentId(s.getId()).stream()
            .map(sp -> StudentDetailResponse.StudentParentInfo.builder()
                .parentId(sp.getParent().getId())
                .fullName(sp.getParent().getFullName())
                .phone(sp.getParent().getPhone())
                .address(sp.getParent().getAddress())
                .relation(sp.getRelation())
                .isPrimary(sp.getIsPrimary())
                .build())
            .collect(Collectors.toList());

        List<PaymentResponse> payments = s.getPayments().stream()
            .sorted(Comparator.comparing(Payment::getPaymentDate, Comparator.nullsLast(Comparator.reverseOrder())))
            .limit(5)
            .map(p -> PaymentResponse.builder()
                .id(p.getId()).uuid(p.getUuid())
                .studentId(s.getId())
                .studentName(s.getFirstName() + " " + s.getLastName())
                .amount(p.getAmount()).paymentDate(p.getPaymentDate())
                .paymentMethod(p.getPaymentMethod()).status(p.getStatus())
                .periodFrom(p.getPeriodFrom()).periodTo(p.getPeriodTo())
                .build())
            .collect(Collectors.toList());

        return StudentDetailResponse.builder()
            .id(s.getId()).uuid(s.getUuid())
            .firstName(s.getFirstName()).lastName(s.getLastName())
            .phone(s.getPhone()).parentPhone(s.getParentPhone())
            .birthDate(s.getBirthDate()).gender(s.getGender())
            .marketingSource(s.getMarketingSource())
            .status(s.getStatus()).notes(s.getNotes())
            .address(s.getAddress()).photoUrl(s.getPhotoUrl())
            .admissionNumber(s.getAdmissionNumber()).admissionDate(s.getAdmissionDate())
            .referralStudentId(s.getReferralStudent() != null ? s.getReferralStudent().getId() : null)
            .activeGroups(groups).recentPayments(payments).parents(parents)
            .createdAt(s.getCreatedAt())
            .build();
    }

    private String esc(String val) {
        if (val == null) return "";
        return "\"" + val.replace("\"", "\"\"") + "\"";
    }
}
