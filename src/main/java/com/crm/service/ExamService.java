package com.crm.service;

import com.crm.dto.request.ExamRequest;
import com.crm.dto.request.ExamResultRequest;
import com.crm.dto.response.*;
import com.crm.entity.*;
import com.crm.entity.enums.StudentStatus;
import com.crm.exception.BadRequestException;
import com.crm.exception.DuplicateResourceException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private final TeacherAccessService teacherAccessService;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public PageResponse<ExamResponse> getAllExams(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Exam> p;
        var teacherScope = teacherAccessService.resolveTeacherScope();
        if (teacherScope.isPresent()) {
            p = examRepository.findActiveByTeacherId(teacherScope.get().getId(), pageable);
        } else {
            p = examRepository.findByIsActiveTrue(pageable);
        }
        return PageResponse.<ExamResponse>builder()
            .content(p.getContent().stream().map(this::toExamResponse).collect(Collectors.toList()))
            .pageNumber(page).pageSize(size)
            .totalElements(p.getTotalElements()).totalPages(p.getTotalPages()).last(p.isLast())
            .build();
    }

    @Transactional(readOnly = true)
    public ExamResponse getExamById(Long id) {
        Exam exam = findExamById(id);
        assertExamAccess(exam);
        return toExamResponse(exam);
    }

    @Transactional
    public ExamResponse createExam(ExamRequest request) {
        if (request.getGroupId() != null) {
            teacherAccessService.assertOwnsGroup(request.getGroupId());
        }
        Exam exam = buildExam(new Exam(), request);
        if (teacherAccessService.isCurrentUserTeacher()) {
            exam.setTeacher(teacherAccessService.getCurrentTeacherOrThrow());
        }
        exam.setIsActive(true);
        Exam saved = examRepository.save(exam);
        if (saved.getGroup() != null) {
            autoRegisterGroupStudents(saved);
        }
        return toExamResponse(saved);
    }

    @Transactional
    public ExamResponse updateExam(Long id, ExamRequest request) {
        Exam exam = findExamById(id);
        assertExamAccess(exam);
        if (request.getGroupId() != null) {
            teacherAccessService.assertOwnsGroup(request.getGroupId());
        }
        buildExam(exam, request);
        return toExamResponse(examRepository.save(exam));
    }

    @Transactional
    public void deleteExam(Long id) {
        Exam exam = findExamById(id);
        assertExamAccess(exam);
        exam.setIsActive(false);
        examRepository.save(exam);
    }

    @Transactional(readOnly = true)
    public List<ExamResultResponse> getResultsByExam(Long examId) {
        Exam exam = findExamById(examId);
        assertExamAccess(exam);

        Map<Long, ExamResult> byStudent = examResultRepository.findByExamId(examId).stream()
            .collect(Collectors.toMap(r -> r.getStudent().getId(), r -> r, (a, b) -> a, LinkedHashMap::new));

        List<ExamResultResponse> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();

        for (ExamRegistration reg : examRegistrationRepository.findByExamId(examId)) {
            Long studentId = reg.getStudent().getId();
            seen.add(studentId);
            ExamResult existing = byStudent.get(studentId);
            if (existing != null) {
                out.add(toResultResponse(existing));
            } else {
                out.add(toEmptyResultResponse(exam, reg.getStudent()));
            }
        }

        for (ExamResult r : byStudent.values()) {
            if (!seen.contains(r.getStudent().getId())) {
                out.add(toResultResponse(r));
            }
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<ExamResultResponse> getResultsByStudent(Long studentId) {
        teacherAccessService.assertOwnsStudent(studentId);
        return examResultRepository.findByStudentId(studentId).stream()
            .map(this::toResultResponse).collect(Collectors.toList());
    }

    @Transactional
    public ExamResultResponse addResult(Long examId, ExamResultRequest request) {
        Exam exam = findExamById(examId);
        assertExamAccess(exam);

        if (request.getStudentId() == null) {
            throw new BadRequestException("Student ID is required");
        }

        if (examResultRepository.findByExamIdAndStudentId(examId, request.getStudentId()).isPresent()) {
            throw new DuplicateResourceException("Result for this student already exists in exam");
        }

        Student student = studentRepository.findById(request.getStudentId())
            .orElseThrow(() -> new ResourceNotFoundException("Student", request.getStudentId()));

        BigDecimal score = request.resolveScore();
        ExamResult result = ExamResult.builder()
            .exam(exam).student(student)
            .marksObtained(score)
            .grade(request.getGrade())
            .remarks(request.resolveNotes())
            .isPassed(computePassed(score, exam.getPassMarks()))
            .build();

        return toResultResponse(examResultRepository.save(result));
    }

    @Transactional
    public ExamResultResponse updateResult(Long examId, Long resultId, ExamResultRequest request) {
        Exam exam = findExamById(examId);
        assertExamAccess(exam);

        ExamResult result = examResultRepository.findById(resultId)
            .orElseThrow(() -> new ResourceNotFoundException("ExamResult", resultId));
        if (!exam.getId().equals(result.getExam().getId())) {
            throw new BadRequestException("Natija ushbu imtihonga tegishli emas");
        }

        String editNote = request.getEditNote();
        if (editNote == null || editNote.isBlank()) {
            throw new BadRequestException("O'zgartirish sababi kiritilishi shart");
        }

        BigDecimal oldScore = result.getMarksObtained();
        BigDecimal newScore = request.resolveScore() != null ? request.resolveScore() : oldScore;

        String changeLog = formatScoreChange(oldScore, newScore) + ", sabab: " + editNote.trim();

        result.setMarksObtained(newScore);
        if (request.getGrade() != null) {
            result.setGrade(request.getGrade());
        }
        if (request.resolveNotes() != null) {
            result.setRemarks(request.resolveNotes());
        }
        result.setIsPassed(computePassed(newScore, exam.getPassMarks()));
        result.setEditNote(changeLog);
        result.setEditedAt(LocalDateTime.now());
        result.setEditedBy(currentUserOrNull());

        return toResultResponse(examResultRepository.save(result));
    }

    /** Backward-compat: examId siz yangilash */
    @Transactional
    public ExamResultResponse updateResult(Long resultId, ExamResultRequest request) {
        ExamResult result = examResultRepository.findById(resultId)
            .orElseThrow(() -> new ResourceNotFoundException("ExamResult", resultId));
        return updateResult(result.getExam().getId(), resultId, request);
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
            .findFirstByStudent_IdAndPeriodEndIsNotNullOrderByPeriodEndDesc(studentId);

        if (lastPayment.isPresent() && lastPayment.get().getPeriodEnd() != null
            && exam.getExamDate() != null) {
            LocalDate periodEnd = lastPayment.get().getPeriodEnd();
            result.put("lastPaidUntil", periodEnd);

            if (periodEnd.isBefore(exam.getExamDate())) {
                long days = ChronoUnit.DAYS.between(periodEnd, exam.getExamDate());
                result.put("unpaidDays", days);

                BigDecimal monthlyPrice = studentGroupRepository.findActiveByStudentId(studentId).stream()
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
        List<StudentGroup> active = studentGroupRepository.findActiveByStudentId(studentId);
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

    private void assertExamAccess(Exam exam) {
        if (!teacherAccessService.isCurrentUserTeacher()) {
            return;
        }
        Teacher teacher = teacherAccessService.getCurrentTeacherOrThrow();
        boolean ownsTeacher = exam.getTeacher() != null && teacher.getId().equals(exam.getTeacher().getId());
        boolean ownsGroup = exam.getGroup() != null && exam.getGroup().getTeacher() != null
            && teacher.getId().equals(exam.getGroup().getTeacher().getId());
        if (!ownsTeacher && !ownsGroup) {
            throw new com.crm.exception.ForbiddenException("Bu imtihon sizga tegishli emas");
        }
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

    private void autoRegisterGroupStudents(Exam exam) {
        Long groupId = exam.getGroup().getId();
        List<StudentGroup> enrollments = studentGroupRepository.findActiveByGroupId(groupId);
        for (StudentGroup sg : enrollments) {
            Student student = sg.getStudent();
            if (student == null || student.getStatus() != StudentStatus.ACTIVE) {
                continue;
            }
            if (examRegistrationRepository.existsByExamIdAndStudentId(exam.getId(), student.getId())) {
                continue;
            }
            ExamRegistration reg = ExamRegistration.builder()
                .exam(exam)
                .student(student)
                .paymentStatus("PENDING")
                .amountDue(BigDecimal.ZERO)
                .amountPaid(BigDecimal.ZERO)
                .status("REGISTERED")
                .notes("Guruhdan avtomatik ro'yxatga olindi")
                .build();
            examRegistrationRepository.save(reg);
        }
    }

    static Boolean computePassed(BigDecimal score, BigDecimal passMarks) {
        if (score == null || passMarks == null) {
            return null;
        }
        return score.compareTo(passMarks) >= 0;
    }

    private static String formatScoreChange(BigDecimal oldScore, BigDecimal newScore) {
        String from = oldScore != null ? oldScore.stripTrailingZeros().toPlainString() : "—";
        String to = newScore != null ? newScore.stripTrailingZeros().toPlainString() : "—";
        return from + " → " + to;
    }

    private User currentUserOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || "anonymousUser".equals(auth.getName())) {
            return null;
        }
        return userRepository.findByUsername(auth.getName()).orElse(null);
    }

    private ExamResultResponse toEmptyResultResponse(Exam exam, Student student) {
        return ExamResultResponse.builder()
            .id(null)
            .examId(exam.getId())
            .examName(exam.getExamName())
            .studentId(student.getId())
            .studentName(student.getFirstName() + " " + student.getLastName())
            .marksObtained(null)
            .totalMarks(exam.getTotalMarks())
            .passMarks(exam.getPassMarks())
            .grade(null)
            .remarks(null)
            .isPassed(null)
            .createdAt(null)
            .build();
    }

    private ExamResultResponse toResultResponse(ExamResult r) {
        String editedByName = null;
        if (r.getEditedBy() != null) {
            editedByName = ((r.getEditedBy().getFirstName() != null ? r.getEditedBy().getFirstName() : "")
                + " "
                + (r.getEditedBy().getLastName() != null ? r.getEditedBy().getLastName() : "")).trim();
            if (editedByName.isEmpty()) {
                editedByName = r.getEditedBy().getUsername();
            }
        }
        return ExamResultResponse.builder()
            .id(r.getId())
            .examId(r.getExam().getId()).examName(r.getExam().getExamName())
            .studentId(r.getStudent().getId())
            .studentName(r.getStudent().getFirstName() + " " + r.getStudent().getLastName())
            .marksObtained(r.getMarksObtained())
            .totalMarks(r.getExam().getTotalMarks())
            .passMarks(r.getExam().getPassMarks())
            .grade(r.getGrade()).remarks(r.getRemarks()).isPassed(r.getIsPassed())
            .editNote(r.getEditNote())
            .editedAt(r.getEditedAt())
            .editedBy(editedByName)
            .createdAt(r.getCreatedAt()).build();
    }
}
