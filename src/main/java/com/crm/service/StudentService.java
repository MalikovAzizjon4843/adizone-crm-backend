package com.crm.service;

import com.crm.dto.request.StudentRequest;
import com.crm.dto.response.*;
import com.crm.entity.Payment;
import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.AttendanceStatus;
import com.crm.entity.enums.MarketingSource;
import com.crm.entity.enums.StudentStatus;
import com.crm.exception.DuplicateResourceException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.AttendanceRepository;
import com.crm.repository.PaymentRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentParentRepository;
import com.crm.repository.StudentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StudentService {

    private final StudentRepository studentRepository;
    private final StudentParentRepository studentParentRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final PaymentRepository paymentRepository;
    private final AttendanceRepository attendanceRepository;

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

    @Transactional(readOnly = true)
    public List<StudentResponse> getArchivedStudents() {
        return studentRepository.findArchivedOrFrozenWithBalanceSignals(LocalDate.now()).stream()
            .map(this::toResponse)
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<StudentDetailResponse.GroupSummary> getStudentGroupHistory(Long studentId) {
        findById(studentId);
        return studentGroupRepository.findByStudentIdOrderByJoinDateDesc(studentId).stream()
            .map(this::toGroupSummary)
            .collect(Collectors.toList());
    }

    @Transactional
    public StudentResponse createStudent(StudentRequest request) {
        if (studentRepository.findByPhone(request.getPhone()).isPresent()) {
            throw new DuplicateResourceException("Student with phone already exists: " + request.getPhone());
        }

        Student student = buildFromRequest(new Student(), request);

        // Auto-generate admission number if not provided:
        if (request.getAdmissionNumber() == null || 
            request.getAdmissionNumber().isBlank()) {
            String admNum = "ADM-" + String.format("%05d",
                studentRepository.count() + 1);
            student.setAdmissionNumber(admNum);
        } else {
            student.setAdmissionNumber(request.getAdmissionNumber());
        }

        // Set admission date if not provided:
        if (request.getAdmissionDate() == null) {
            student.setAdmissionDate(LocalDate.now());
        } else {
            student.setAdmissionDate(request.getAdmissionDate());
        }

        // Default status if not provided:
        if (request.getStatus() == null || request.getStatus().isBlank()) {
            student.setStatus(StudentStatus.ACTIVE);
        }

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
        
        if (req.getMarketingSource() != null && !req.getMarketingSource().isBlank()) {
            try {
                s.setMarketingSource(MarketingSource.valueOf(req.getMarketingSource().toUpperCase()));
            } catch (IllegalArgumentException e) {
                s.setMarketingSource(MarketingSource.OTHER);
            }
        }
        
        if (req.getStatus() != null && !req.getStatus().isBlank()) {
            try {
                s.setStatus(StudentStatus.valueOf(req.getStatus().toUpperCase()));
            } catch (IllegalArgumentException e) {
                s.setStatus(StudentStatus.ACTIVE);
            }
        }
        
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
        List<StudentDetailResponse.GroupSummary> activeGroups = s.getStudentGroups().stream()
            .filter(sg -> Boolean.TRUE.equals(sg.getIsActive()))
            .map(this::toGroupSummary)
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

        List<StudentDetailResponse.PaymentSummary> paymentHistory = paymentRepository
            .findByStudentIdOrderByPaymentDateDesc(s.getId()).stream()
            .limit(100)
            .map(this::toPaymentSummary)
            .collect(Collectors.toList());

        Map<String, Integer> attendanceSummary = new LinkedHashMap<>();
        attendanceSummary.put("present", 0);
        attendanceSummary.put("absent", 0);
        attendanceSummary.put("late", 0);
        for (Object[] row : attendanceRepository.countByStudentGrouped(s.getId())) {
            AttendanceStatus st = (AttendanceStatus) row[0];
            int c = ((Number) row[1]).intValue();
            if (st != null) {
                attendanceSummary.put(st.name().toLowerCase(Locale.ROOT), c);
            }
        }

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
            .activeGroups(activeGroups)
            .paymentHistory(paymentHistory)
            .attendanceSummary(attendanceSummary)
            .parents(parents)
            .createdAt(s.getCreatedAt())
            .build();
    }

    private StudentDetailResponse.GroupSummary toGroupSummary(StudentGroup sg) {
        BigDecimal monthlyPrice = sg.getMonthlyPriceOverride() != null
            ? sg.getMonthlyPriceOverride()
            : sg.getGroup().getCourse().getMonthlyPrice();
        return StudentDetailResponse.GroupSummary.builder()
            .groupId(sg.getGroup().getId())
            .groupName(sg.getGroup().getGroupName())
            .courseName(sg.getGroup().getCourse().getCourseName())
            .teacherName(sg.getGroup().getTeacher() != null
                ? sg.getGroup().getTeacher().getFirstName() + " " + sg.getGroup().getTeacher().getLastName() : null)
            .joinDate(sg.getJoinDate())
            .leaveDate(sg.getLeaveDate())
            .isActive(sg.getIsActive())
            .monthlyPrice(monthlyPrice)
            .build();
    }

    private StudentDetailResponse.PaymentSummary toPaymentSummary(Payment p) {
        return StudentDetailResponse.PaymentSummary.builder()
            .receiptNumber(p.getReceiptNumber())
            .amount(p.getAmount())
            .formattedAmount(formatUzs(p.getAmount()))
            .paymentDate(p.getPaymentDate())
            .groupName(p.getGroup() != null ? p.getGroup().getGroupName() : null)
            .status(p.getStatus())
            .build();
    }

    private static String formatUzs(BigDecimal amount) {
        if (amount == null) {
            return "0 so'm";
        }
        long v = amount.setScale(0, RoundingMode.HALF_UP).longValue();
        return String.format(Locale.US, "%,d", v).replace(',', ' ') + " so'm";
    }

    private String esc(String val) {
        if (val == null) return "";
        return "\"" + val.replace("\"", "\"\"") + "\"";
    }
}
