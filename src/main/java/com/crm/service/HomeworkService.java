package com.crm.service;

import com.crm.dto.request.HomeworkGradeRequest;
import com.crm.dto.request.HomeworkRequest;
import com.crm.dto.request.HomeworkSubmissionRequest;
import com.crm.dto.response.*;
import com.crm.entity.*;
import com.crm.entity.enums.HomeworkSubmissionStatus;
import com.crm.exception.CodedException;
import com.crm.exception.DuplicateResourceException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.*;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Uy vazifalari (phase6-api §4). SA/A — hammasi; TEACHER — faqat o'z guruhlari (guruh o'qituvchisi) yoki o'zi
 * yaratgan vazifa. O'quvchi login qilmaydi: topshirdi/kech/topshirmadi, baho va izohni o'qituvchi belgilaydi
 * ({@code PUT /{id}/students}).
 */
@Service
@RequiredArgsConstructor
public class HomeworkService {

    private final HomeworkRepository homeworkRepository;
    private final HomeworkSubmissionRepository submissionRepository;
    private final StudentRepository studentRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final SubjectRepository subjectRepository;
    private final ClassRepository classRepository;
    private final GroupRepository groupRepository;
    private final TeacherRepository teacherRepository;
    private final TeacherAccessService teacherAccessService;
    private final Clock billingClock;

    /** {@code GET /api/homework} filtri — hammasi ixtiyoriy; {@code from}/{@code to} — topshirish muddati. */
    public record Filter(Long groupId, LocalDate from, LocalDate to) {
    }

    // ── O'qish ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<HomeworkResponse> getAllHomeworks(Filter f, int page, int size) {
        if (f.groupId() != null) {
            teacherAccessService.assertOwnsGroup(f.groupId());
        }
        if (f.from() != null && f.to() != null && f.to().isBefore(f.from())) {
            throw CodedException.badRequest("homework.dates.invalid");
        }
        Long teacherId = teacherAccessService.resolveTeacherScope().map(Teacher::getId).orElse(null);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("dueDate"), Sort.Order.desc("id")));
        return buildPage(homeworkRepository.findAll(listSpec(f, teacherId), pageable), page, size);
    }

    /** @deprecated {@code GET /api/homework?groupId=}. */
    @Deprecated
    @Transactional(readOnly = true)
    public PageResponse<HomeworkResponse> getHomeworksByGroup(Long groupId, int page, int size) {
        return getAllHomeworks(new Filter(groupId, null, null), page, size);
    }

    @Transactional(readOnly = true)
    public HomeworkResponse getHomeworkById(Long id) {
        Homework hw = findById(id);
        assertHomeworkAccess(hw);
        return toResponse(hw);
    }

    // ── Yozish ──────────────────────────────────────────────────────────

    @Transactional
    public HomeworkResponse createHomework(HomeworkRequest request) {
        if (request.getGroupId() == null) {
            throw CodedException.badRequest("homework.group.required");
        }
        teacherAccessService.assertOwnsGroup(request.getGroupId());
        Homework hw = buildHomework(new Homework(), request);
        if (teacherAccessService.isCurrentUserTeacher()) {
            hw.setTeacher(teacherAccessService.getCurrentTeacherOrThrow());
        } else if (hw.getTeacher() == null && hw.getGroup() != null) {
            hw.setTeacher(hw.getGroup().getTeacher());
        }
        hw.setIsActive(true);
        if (hw.getAssignedDate() == null) {
            hw.setAssignedDate(LocalDate.now(billingClock));
        }
        assertDates(hw);
        return toResponse(homeworkRepository.save(hw));
    }

    @Transactional
    public HomeworkResponse updateHomework(Long id, HomeworkRequest request) {
        Homework hw = findActive(id);
        assertHomeworkAccess(hw);
        if (request.getGroupId() != null) {
            teacherAccessService.assertOwnsGroup(request.getGroupId());
        }
        buildHomework(hw, request);
        if (teacherAccessService.isCurrentUserTeacher()) {
            hw.setTeacher(teacherAccessService.getCurrentTeacherOrThrow());
        }
        assertDates(hw);
        return toResponse(homeworkRepository.save(hw));
    }

    /** Soft delete (SA/A — controller). */
    @Transactional
    public void deleteHomework(Long id) {
        Homework hw = findById(id);
        assertHomeworkAccess(hw);
        hw.setIsActive(false);
        homeworkRepository.save(hw);
    }

    // ── O'quvchilar bo'yicha holat ──────────────────────────────────────

    /** Guruhning hozirgi o'quvchilari + guruhdan chiqqan, lekin belgilanganlar; belgisizlar — NOT_SUBMITTED. */
    @Transactional(readOnly = true)
    public HomeworkRosterResponse roster(Long homeworkId) {
        Homework hw = findActive(homeworkId);
        assertHomeworkAccess(hw);
        return buildRoster(hw);
    }

    /** Ko'plikda belgilash (upsert): hammasi yoki hech biri; xatoda {@code data.index}. */
    @Transactional
    public HomeworkRosterResponse grade(Long homeworkId, HomeworkGradeRequest request) {
        Homework hw = findActive(homeworkId);
        assertHomeworkAccess(hw);
        Set<Long> inGroup = groupStudentIds(hw);
        Map<Long, HomeworkSubmission> existing = submissionRepository.findByHomeworkId(hw.getId()).stream()
            .collect(Collectors.toMap(s -> s.getStudent().getId(), s -> s, (a, b) -> a));
        Set<Long> seen = new HashSet<>();
        List<HomeworkGradeRequest.Item> items = request.getItems();
        for (int i = 0; i < items.size(); i++) {
            HomeworkGradeRequest.Item item = items.get(i);
            try {
                if (!seen.add(item.getStudentId())) {
                    throw CodedException.badRequest("homework.student.duplicate", item.getStudentId());
                }
                HomeworkSubmission sub = existing.get(item.getStudentId());
                if (sub == null && !inGroup.contains(item.getStudentId())) {
                    throw CodedException.badRequest("homework.student.notInGroup", item.getStudentId());
                }
                HomeworkSubmissionStatus status = HomeworkSubmissionStatus.parseOrNull(item.getStatus());
                if (status == null) {
                    throw CodedException.badRequest("homework.status.invalid", item.getStatus());
                }
                BigDecimal mark = item.getMarksObtained();
                validateMark(hw, status, mark);
                if (sub == null) {
                    sub = HomeworkSubmission.builder()
                        .homework(hw)
                        .student(studentRepository.findById(item.getStudentId())
                            .orElseThrow(() -> new ResourceNotFoundException("Student", item.getStudentId())))
                        .build();
                }
                sub.setStatus(status.name());
                sub.setMarksObtained(status == HomeworkSubmissionStatus.NOT_SUBMITTED ? null : mark);
                sub.setRemarks(trimToNull(item.getRemarks()));
                sub.setSubmittedAt(status == HomeworkSubmissionStatus.NOT_SUBMITTED ? null
                    : (item.getSubmittedAt() != null ? item.getSubmittedAt()
                        : (sub.getSubmittedAt() != null ? sub.getSubmittedAt() : LocalDateTime.now(billingClock))));
                existing.put(item.getStudentId(), submissionRepository.save(sub));
            } catch (CodedException e) {
                Map<String, Object> data = new LinkedHashMap<>();
                if (e.getData() != null) {
                    data.putAll(e.getData());
                }
                data.put("index", i);
                throw e.withData(data);
            }
        }
        return buildRoster(hw);
    }

    // ── eski submission endpointlari (deprecated) ───────────────────────

    @Transactional(readOnly = true)
    public List<HomeworkSubmissionResponse> getSubmissions(Long homeworkId) {
        assertHomeworkAccess(findById(homeworkId));
        return submissionRepository.findByHomeworkId(homeworkId).stream()
            .map(this::toSubmissionResponse).collect(Collectors.toList());
    }

    /** @deprecated {@code PUT /{id}/students}. Endi egalik, guruh a'zoligi va holat tekshiriladi. */
    @Deprecated
    @Transactional
    public HomeworkSubmissionResponse addSubmission(Long homeworkId, HomeworkSubmissionRequest request) {
        Homework hw = findActive(homeworkId);
        assertHomeworkAccess(hw);
        if (submissionRepository.findByHomeworkIdAndStudentId(homeworkId, request.getStudentId()).isPresent()) {
            throw new DuplicateResourceException("Submission already exists for this student");
        }
        if (!groupStudentIds(hw).contains(request.getStudentId())) {
            throw CodedException.badRequest("homework.student.notInGroup", request.getStudentId());
        }
        HomeworkSubmissionStatus status = request.getStatus() == null ? HomeworkSubmissionStatus.SUBMITTED
            : HomeworkSubmissionStatus.parseOrNull(request.getStatus());
        if (status == null) {
            throw CodedException.badRequest("homework.status.invalid", request.getStatus());
        }
        validateMark(hw, status, request.getMarksObtained());
        Student student = studentRepository.findById(request.getStudentId())
            .orElseThrow(() -> new ResourceNotFoundException("Student", request.getStudentId()));
        HomeworkSubmission sub = HomeworkSubmission.builder()
            .homework(hw).student(student)
            .submittedAt(request.getSubmittedAt())
            .fileUrl(request.getFileUrl())
            .remarks(request.getRemarks())
            .marksObtained(request.getMarksObtained())
            .status(status.name())
            .build();
        return toSubmissionResponse(submissionRepository.save(sub));
    }

    /** @deprecated {@code PUT /{id}/students}. Endi egalik va holat tekshiriladi. */
    @Deprecated
    @Transactional
    public HomeworkSubmissionResponse updateSubmission(Long submissionId, HomeworkSubmissionRequest request) {
        HomeworkSubmission sub = submissionRepository.findById(submissionId)
            .orElseThrow(() -> new ResourceNotFoundException("HomeworkSubmission", submissionId));
        assertHomeworkAccess(sub.getHomework());
        HomeworkSubmissionStatus status = request.getStatus() == null
            ? HomeworkSubmissionStatus.fromDb(sub.getStatus()) : HomeworkSubmissionStatus.parseOrNull(request.getStatus());
        if (status == null) {
            throw CodedException.badRequest("homework.status.invalid", request.getStatus());
        }
        validateMark(sub.getHomework(), status, request.getMarksObtained());
        sub.setMarksObtained(request.getMarksObtained());
        sub.setRemarks(request.getRemarks());
        sub.setStatus(status.name());
        return toSubmissionResponse(submissionRepository.save(sub));
    }

    public Homework findById(Long id) {
        return homeworkRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Homework", id));
    }

    // ── yordamchilar ────────────────────────────────────────────────────

    private Homework findActive(Long id) {
        Homework hw = findById(id);
        if (!Boolean.TRUE.equals(hw.getIsActive())) {
            throw new ResourceNotFoundException("Homework", id);
        }
        return hw;
    }

    private static Specification<Homework> listSpec(Filter f, Long teacherId) {
        return (root, query, cb) -> {
            List<Predicate> and = new ArrayList<>();
            and.add(cb.isTrue(root.get("isActive")));
            if (f.groupId() != null) {
                and.add(cb.equal(root.get("group").get("id"), f.groupId()));
            }
            if (f.from() != null) {
                and.add(cb.greaterThanOrEqualTo(root.get("dueDate"), f.from()));
            }
            if (f.to() != null) {
                and.add(cb.lessThanOrEqualTo(root.get("dueDate"), f.to()));
            }
            if (teacherId != null) {
                Join<Homework, Group> group = root.join("group", JoinType.LEFT);
                and.add(cb.or(cb.equal(root.get("teacher").get("id"), teacherId),
                    cb.equal(group.get("teacher").get("id"), teacherId)));
            }
            return cb.and(and.toArray(Predicate[]::new));
        };
    }

    private Set<Long> groupStudentIds(Homework hw) {
        if (hw.getGroup() == null) {
            return Set.of();
        }
        return studentGroupRepository.findActiveByGroupId(hw.getGroup().getId()).stream()
            .map(sg -> sg.getStudent().getId()).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private HomeworkRosterResponse buildRoster(Homework hw) {
        Map<Long, Student> students = new LinkedHashMap<>();
        if (hw.getGroup() != null) {
            studentGroupRepository.findActiveByGroupId(hw.getGroup().getId())
                .forEach(sg -> students.put(sg.getStudent().getId(), sg.getStudent()));
        }
        Set<Long> inGroup = new HashSet<>(students.keySet());
        Map<Long, HomeworkSubmission> subs = new HashMap<>();
        for (HomeworkSubmission s : submissionRepository.findByHomeworkId(hw.getId())) {
            subs.put(s.getStudent().getId(), s);
            students.putIfAbsent(s.getStudent().getId(), s.getStudent());
        }
        List<HomeworkRosterResponse.Row> rows = new ArrayList<>();
        int submitted = 0;
        int late = 0;
        int graded = 0;
        BigDecimal markSum = BigDecimal.ZERO;
        for (Student st : students.values()) {
            HomeworkSubmission s = subs.get(st.getId());
            HomeworkSubmissionStatus status = s != null ? HomeworkSubmissionStatus.fromDb(s.getStatus())
                : HomeworkSubmissionStatus.NOT_SUBMITTED;
            if (status == HomeworkSubmissionStatus.SUBMITTED) {
                submitted++;
            } else if (status == HomeworkSubmissionStatus.LATE) {
                late++;
            }
            if (s != null && s.getMarksObtained() != null) {
                graded++;
                markSum = markSum.add(s.getMarksObtained());
            }
            rows.add(HomeworkRosterResponse.Row.builder()
                .studentId(st.getId())
                .studentName(name(st))
                .inGroup(inGroup.contains(st.getId()))
                .submissionId(s != null ? s.getId() : null)
                .status(status.name())
                .marksObtained(s != null ? s.getMarksObtained() : null)
                .remarks(s != null ? s.getRemarks() : null)
                .submittedAt(s != null ? s.getSubmittedAt() : null)
                .fileUrl(s != null ? s.getFileUrl() : null)
                .updatedAt(s != null ? s.getUpdatedAt() : null)
                .build());
        }
        rows.sort(Comparator.comparing(HomeworkRosterResponse.Row::getStudentName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(HomeworkRosterResponse.Row::getStudentId));
        return HomeworkRosterResponse.builder()
            .homework(toResponse(hw))
            .summary(HomeworkRosterResponse.Summary.builder()
                .total(rows.size()).submitted(submitted).late(late)
                .notSubmitted(rows.size() - submitted - late).graded(graded)
                .averageMark(graded == 0 ? null : markSum.divide(BigDecimal.valueOf(graded), 2, RoundingMode.HALF_UP))
                .build())
            .students(rows)
            .build();
    }

    private static void validateMark(Homework hw, HomeworkSubmissionStatus status, BigDecimal mark) {
        if (mark == null) {
            return;
        }
        if (status == HomeworkSubmissionStatus.NOT_SUBMITTED) {
            throw CodedException.badRequest("homework.mark.notSubmitted");
        }
        if (mark.signum() < 0 || (hw.getMarks() != null && mark.compareTo(hw.getMarks()) > 0)) {
            throw CodedException.badRequest("homework.mark.range", hw.getMarks() != null ? hw.getMarks() : "∞");
        }
    }

    private static void assertDates(Homework hw) {
        if (hw.getAssignedDate() != null && hw.getDueDate() != null && hw.getDueDate().isBefore(hw.getAssignedDate())) {
            throw CodedException.badRequest("homework.dates.invalid");
        }
        if (hw.getMarks() != null && hw.getMarks().signum() < 0) {
            throw CodedException.badRequest("homework.mark.range", hw.getMarks());
        }
    }

    private void assertHomeworkAccess(Homework hw) {
        if (!teacherAccessService.isCurrentUserTeacher()) {
            return;
        }
        Teacher teacher = teacherAccessService.getCurrentTeacherOrThrow();
        boolean ownsTeacher = hw.getTeacher() != null && teacher.getId().equals(hw.getTeacher().getId());
        boolean ownsGroup = hw.getGroup() != null && hw.getGroup().getTeacher() != null
            && teacher.getId().equals(hw.getGroup().getTeacher().getId());
        if (!ownsTeacher && !ownsGroup) {
            throw new com.crm.exception.ForbiddenException("Bu uy vazifasi sizga tegishli emas");
        }
    }

    private Homework buildHomework(Homework hw, HomeworkRequest req) {
        hw.setTitle(req.getTitle().trim());
        hw.setDescription(req.getDescription());
        hw.setDueDate(req.getDueDate());
        hw.setMarks(req.getMarks());
        if (req.getAssignedDate() != null) hw.setAssignedDate(req.getAssignedDate());
        if (req.getSubjectId() != null)
            hw.setSubject(subjectRepository.findById(req.getSubjectId())
                .orElseThrow(() -> new ResourceNotFoundException("Subject", req.getSubjectId())));
        if (req.getClassId() != null)
            hw.setClassEntity(classRepository.findById(req.getClassId())
                .orElseThrow(() -> new ResourceNotFoundException("Class", req.getClassId())));
        if (req.getGroupId() != null)
            hw.setGroup(groupRepository.findById(req.getGroupId())
                .orElseThrow(() -> new ResourceNotFoundException("Group", req.getGroupId())));
        if (req.getTeacherId() != null)
            hw.setTeacher(teacherRepository.findById(req.getTeacherId())
                .orElseThrow(() -> new ResourceNotFoundException("Teacher", req.getTeacherId())));
        String url = trimToNull(req.getAttachmentUrl());
        if (url != null && !url.startsWith(FileStorageService.URL_PREFIX)) {
            throw CodedException.badRequest("homework.attachment.invalid");
        }
        hw.setAttachmentUrl(url);
        hw.setAttachmentName(url != null ? trimToNull(req.getAttachmentName()) : null);
        return hw;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String name(Student s) {
        return ((s.getFirstName() != null ? s.getFirstName() : "") + " "
            + (s.getLastName() != null ? s.getLastName() : "")).trim();
    }

    private PageResponse<HomeworkResponse> buildPage(Page<Homework> p, int page, int size) {
        return PageResponse.<HomeworkResponse>builder()
            .content(p.getContent().stream().map(this::toResponse).collect(Collectors.toList()))
            .pageNumber(page).pageSize(size)
            .totalElements(p.getTotalElements()).totalPages(p.getTotalPages()).last(p.isLast())
            .build();
    }

    private HomeworkResponse toResponse(Homework hw) {
        return HomeworkResponse.builder()
            .id(hw.getId()).uuid(hw.getUuid()).title(hw.getTitle()).description(hw.getDescription())
            .subjectId(hw.getSubject() != null ? hw.getSubject().getId() : null)
            .subjectName(hw.getSubject() != null ? hw.getSubject().getSubjectName() : null)
            .classId(hw.getClassEntity() != null ? hw.getClassEntity().getId() : null)
            .className(hw.getClassEntity() != null ? hw.getClassEntity().getClassName() : null)
            .groupId(hw.getGroup() != null ? hw.getGroup().getId() : null)
            .groupName(hw.getGroup() != null ? hw.getGroup().getGroupName() : null)
            .teacherId(hw.getTeacher() != null ? hw.getTeacher().getId() : null)
            .teacherName(hw.getTeacher() != null
                ? hw.getTeacher().getFirstName() + " " + hw.getTeacher().getLastName() : null)
            .assignedDate(hw.getAssignedDate()).dueDate(hw.getDueDate())
            .marks(hw.getMarks())
            .attachmentUrl(hw.getAttachmentUrl()).attachmentName(hw.getAttachmentName())
            .isActive(hw.getIsActive()).createdAt(hw.getCreatedAt()).build();
    }

    private HomeworkSubmissionResponse toSubmissionResponse(HomeworkSubmission sub) {
        return HomeworkSubmissionResponse.builder()
            .id(sub.getId())
            .homeworkId(sub.getHomework().getId()).homeworkTitle(sub.getHomework().getTitle())
            .studentId(sub.getStudent().getId())
            .studentName(sub.getStudent().getFirstName() + " " + sub.getStudent().getLastName())
            .submittedAt(sub.getSubmittedAt()).fileUrl(sub.getFileUrl())
            .remarks(sub.getRemarks()).marksObtained(sub.getMarksObtained())
            .status(HomeworkSubmissionStatus.fromDb(sub.getStatus()).name()).createdAt(sub.getCreatedAt()).build();
    }
}
