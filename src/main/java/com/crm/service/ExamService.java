package com.crm.service;

import com.crm.dto.request.ExamRequest;
import com.crm.dto.request.ExamResultRequest;
import com.crm.dto.response.*;
import com.crm.entity.*;
import com.crm.exception.BadRequestException;
import com.crm.exception.DuplicateResourceException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ExamService {

    private static final int MIN_PRESENT_DAYS_FOR_EXAM = 8;

    private final ExamRepository examRepository;
    private final ExamResultRepository examResultRepository;
    private final ExamRegistrationRepository examRegistrationRepository;
    private final StudentRepository studentRepository;
    private final ClassRepository classRepository;
    private final GroupRepository groupRepository;
    private final SubjectRepository subjectRepository;
    private final PaymentRepository paymentRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final AttendanceRepository attendanceRepository;
    private final ExamPaymentCalculatorService examPaymentCalculatorService;

    @Transactional(readOnly = true)
    public PageResponse<ExamResponse> getAllExams(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Exam> p = examRepository.findByIsActiveTrue(pageable);
        return PageResponse.<ExamResponse>builder()
            .content(p.getContent().stream().map(this::toExamResponse).collect(Collectors.toList()))
            .pageNumber(page).pageSize(size)
            .totalElements(p.getTotalElements()).totalPages(p.getTotalPages()).last(p.isLast())
            .build();
    }

    @Transactional(readOnly = true)
    public ExamResponse getExamById(Long id) {
        return toExamResponse(findExamById(id));
    }

    @Transactional
    public ExamResponse createExam(ExamRequest request) {
        Exam exam = buildExam(new Exam(), request);
        exam.setIsActive(true);
        return toExamResponse(examRepository.save(exam));
    }

    @Transactional
    public ExamResponse updateExam(Long id, ExamRequest request) {
        Exam exam = findExamById(id);
        buildExam(exam, request);
        return toExamResponse(examRepository.save(exam));
    }

    @Transactional
    public void deleteExam(Long id) {
        Exam exam = findExamById(id);
        exam.setIsActive(false);
        examRepository.save(exam);
    }

    @Transactional(readOnly = true)
    public List<ExamResultResponse> getResultsByExam(Long examId) {
        findExamById(examId);
        return examResultRepository.findByExamId(examId).stream()
            .map(this::toResultResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ExamResultResponse> getResultsByStudent(Long studentId) {
        return examResultRepository.findByStudentId(studentId).stream()
            .map(this::toResultResponse).collect(Collectors.toList());
    }

    @Transactional
    public ExamResultResponse addResult(Long examId, ExamResultRequest request) {
        Exam exam = findExamById(examId);

        if (examResultRepository.findByExamIdAndStudentId(examId, request.getStudentId()).isPresent()) {
            throw new DuplicateResourceException("Result for this student already exists in exam");
        }

        Student student = studentRepository.findById(request.getStudentId())
            .orElseThrow(() -> new ResourceNotFoundException("Student", request.getStudentId()));

        ExamResult result = ExamResult.builder()
            .exam(exam).student(student)
            .marksObtained(request.getMarksObtained())
            .grade(request.getGrade()).remarks(request.getRemarks())
            .isPassed(request.getIsPassed())
            .build();

        return toResultResponse(examResultRepository.save(result));
    }

    @Transactional
    public ExamResultResponse updateResult(Long resultId, ExamResultRequest request) {
        ExamResult result = examResultRepository.findById(resultId)
            .orElseThrow(() -> new ResourceNotFoundException("ExamResult", resultId));
        result.setMarksObtained(request.getMarksObtained());
        result.setGrade(request.getGrade());
        result.setRemarks(request.getRemarks());
        result.setIsPassed(request.getIsPassed());
        return toResultResponse(examResultRepository.save(result));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> calculateExamPaymentPreview(Long examId, Long studentId) {
        Exam exam = findExamById(examId);
        studentRepository.findById(studentId)
            .orElseThrow(() -> new ResourceNotFoundException("Student", studentId));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("examDate", exam.getExamDate());
        result.put("examName", exam.getExamName());

        Optional<Payment> lastPayment = paymentRepository
            .findFirstByStudent_IdAndPeriodToIsNotNullOrderByPeriodToDesc(studentId);

        if (lastPayment.isPresent() && lastPayment.get().getPeriodTo() != null
            && exam.getExamDate() != null) {
            LocalDate periodEnd = lastPayment.get().getPeriodTo();
            result.put("lastPaidUntil", periodEnd);

            if (periodEnd.isBefore(exam.getExamDate())) {
                long days = ChronoUnit.DAYS.between(periodEnd, exam.getExamDate());
                result.put("unpaidDays", days);

                BigDecimal monthlyPrice = studentGroupRepository.findByStudentIdAndIsActiveTrue(studentId).stream()
                    .findFirst()
                    .map(sg -> sg.getMonthlyPriceOverride() != null
                        ? sg.getMonthlyPriceOverride()
                        : (sg.getGroup().getCourse() != null
                            ? sg.getGroup().getCourse().getMonthlyPrice() : BigDecimal.ZERO))
                    .orElse(BigDecimal.ZERO);

                BigDecimal amountDue = examPaymentCalculatorService.calculateExamPayment(
                    periodEnd, exam.getExamDate(), monthlyPrice);
                double dailyRate = monthlyPrice.compareTo(BigDecimal.ZERO) > 0
                    ? monthlyPrice.doubleValue() / 30.0 : 0.0;

                result.put("monthlyPrice", monthlyPrice);
                result.put("dailyRate", dailyRate);
                result.put("amountDue", amountDue);
                result.put("message", days + " kunlik to'lov: "
                    + String.format(Locale.US, "%.0f", amountDue.doubleValue()) + " UZS");
            } else {
                result.put("amountDue", BigDecimal.ZERO);
                result.put("message", "To'lov kerak emas");
            }
        } else {
            result.put("message", "To'lov tarixi topilmadi");
        }

        return result;
    }

    @Transactional(readOnly = true)
    public List<StudentResponse> getEligibleStudents(Long examId) {
        findExamById(examId);
        return studentRepository.findAll().stream()
            .filter(s -> isEligibleForExam(s.getId()))
            .map(this::toStudentResponse)
            .collect(Collectors.toList());
    }

    private boolean isEligibleForExam(Long studentId) {
        long presentDays = attendanceRepository.countPresentDaysForStudent(studentId);
        if (presentDays < MIN_PRESENT_DAYS_FOR_EXAM) {
            return false;
        }
        List<StudentGroup> active = studentGroupRepository.findByStudentIdAndIsActiveTrue(studentId);
        if (active.isEmpty()) {
            return false;
        }
        return active.stream().anyMatch(sg -> "PAID".equals(sg.getPaymentStatus()));
    }

    @Transactional
    public ExamRegistrationResponse registerStudentForExam(Long examId, Long studentId) {
        Exam exam = findExamById(examId);
        Student student = studentRepository.findById(studentId)
            .orElseThrow(() -> new ResourceNotFoundException("Student", studentId));

        if (examRegistrationRepository.existsByExamIdAndStudentId(examId, studentId)) {
            throw new DuplicateResourceException("Student already registered for this exam");
        }

        if (!isEligibleForExam(studentId)) {
            throw new BadRequestException(
                "O'quvchi imtihon uchun mos emas (to'lov holati yoki davomat yetarli emas)");
        }

        Map<String, Object> payPreview = calculateExamPaymentPreview(examId, studentId);
        Object ad = payPreview.get("amountDue");
        BigDecimal amountDue = ad instanceof BigDecimal ? (BigDecimal) ad : BigDecimal.ZERO;

        String payStatus = BigDecimal.ZERO.compareTo(amountDue) >= 0 ? "PAID" : "PENDING";

        ExamRegistration reg = ExamRegistration.builder()
            .exam(exam)
            .student(student)
            .paymentStatus(payStatus)
            .amountDue(amountDue)
            .amountPaid(BigDecimal.ZERO.compareTo(amountDue) >= 0 ? amountDue : BigDecimal.ZERO)
            .status("REGISTERED")
            .build();

        ExamRegistration saved = examRegistrationRepository.save(reg);
        return toRegistrationResponse(saved);
    }

    private StudentResponse toStudentResponse(Student s) {
        return StudentResponse.builder()
            .id(s.getId())
            .uuid(s.getUuid())
            .firstName(s.getFirstName())
            .lastName(s.getLastName())
            .phone(s.getPhone())
            .parentPhone(s.getParentPhone())
            .birthDate(s.getBirthDate())
            .gender(s.getGender())
            .marketingSource(s.getMarketingSource())
            .status(s.getStatus())
            .notes(s.getNotes())
            .address(s.getAddress())
            .photoUrl(s.getPhotoUrl())
            .admissionNumber(s.getAdmissionNumber())
            .admissionDate(s.getAdmissionDate())
            .referralStudentId(s.getReferralStudent() != null ? s.getReferralStudent().getId() : null)
            .createdAt(s.getCreatedAt())
            .build();
    }

    private ExamRegistrationResponse toRegistrationResponse(ExamRegistration r) {
        return ExamRegistrationResponse.builder()
            .id(r.getId())
            .examId(r.getExam().getId())
            .studentId(r.getStudent().getId())
            .studentName(r.getStudent().getFirstName() + " " + r.getStudent().getLastName())
            .paymentStatus(r.getPaymentStatus())
            .amountDue(r.getAmountDue())
            .amountPaid(r.getAmountPaid())
            .registrationDate(r.getRegistrationDate())
            .status(r.getStatus())
            .notes(r.getNotes())
            .build();
    }

    private Exam buildExam(Exam e, ExamRequest req) {
        e.setExamName(req.getExamName());
        e.setExamType(req.getExamType());
        e.setExamDate(req.getExamDate());
        e.setStartTime(req.getStartTime());
        e.setEndTime(req.getEndTime());
        e.setTotalMarks(req.getTotalMarks());
        e.setPassMarks(req.getPassMarks());
        e.setAcademicYear(req.getAcademicYear());
        if (req.getGroupId() != null) {
            groupRepository.findById(req.getGroupId()).ifPresent(group -> {
                e.setGroup(group);
                if (group.getTeacher() != null) {
                    e.setTeacher(group.getTeacher());
                }
            });
        }
        
        if (req.getClassId() != null && !req.getClassId().equals(req.getGroupId())) {
            classRepository.findById(req.getClassId()).ifPresent(e::setClassEntity);
        }
        if (req.getSubjectId() != null) {
            e.setSubject(subjectRepository.findById(req.getSubjectId())
                .orElseThrow(() -> new ResourceNotFoundException("Subject", req.getSubjectId())));
        }
        return e;
    }

    public Exam findExamById(Long id) {
        return examRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Exam", id));
    }

    private ExamResponse toExamResponse(Exam e) {
        Long groupId = e.getGroup() != null ? e.getGroup().getId() : null;
        String groupName = e.getGroup() != null ? e.getGroup().getGroupName() : null;
        return ExamResponse.builder()
            .id(e.getId()).uuid(e.getUuid()).examName(e.getExamName()).examType(e.getExamType())
            .classId(e.getClassEntity() != null ? e.getClassEntity().getId() : null)
            .className(e.getClassEntity() != null ? e.getClassEntity().getClassName() : null)
            .groupId(groupId)
            .groupName(groupName)
            .subjectId(e.getSubject() != null ? e.getSubject().getId() : null)
            .subjectName(e.getSubject() != null ? e.getSubject().getSubjectName() : null)
            .examDate(e.getExamDate()).startTime(e.getStartTime()).endTime(e.getEndTime())
            .totalMarks(e.getTotalMarks()).passMarks(e.getPassMarks())
            .academicYear(e.getAcademicYear()).isActive(e.getIsActive())
            .createdAt(e.getCreatedAt()).build();
    }

    private ExamResultResponse toResultResponse(ExamResult r) {
        return ExamResultResponse.builder()
            .id(r.getId())
            .examId(r.getExam().getId()).examName(r.getExam().getExamName())
            .studentId(r.getStudent().getId())
            .studentName(r.getStudent().getFirstName() + " " + r.getStudent().getLastName())
            .marksObtained(r.getMarksObtained())
            .totalMarks(r.getExam().getTotalMarks())
            .grade(r.getGrade()).remarks(r.getRemarks()).isPassed(r.getIsPassed())
            .createdAt(r.getCreatedAt()).build();
    }
}
