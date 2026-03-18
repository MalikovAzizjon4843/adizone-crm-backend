package com.crm.service;

import com.crm.dto.request.HomeworkRequest;
import com.crm.dto.request.HomeworkSubmissionRequest;
import com.crm.dto.response.*;
import com.crm.entity.*;
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
public class HomeworkService {

    private final HomeworkRepository homeworkRepository;
    private final HomeworkSubmissionRepository submissionRepository;
    private final StudentRepository studentRepository;
    private final SubjectRepository subjectRepository;
    private final ClassRepository classRepository;
    private final GroupRepository groupRepository;
    private final TeacherRepository teacherRepository;

    @Transactional(readOnly = true)
    public PageResponse<HomeworkResponse> getAllHomeworks(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Homework> p = homeworkRepository.findByIsActiveTrue(pageable);
        return buildPage(p, page, size);
    }

    @Transactional(readOnly = true)
    public HomeworkResponse getHomeworkById(Long id) {
        return toResponse(findById(id));
    }

    @Transactional
    public HomeworkResponse createHomework(HomeworkRequest request) {
        Homework hw = buildHomework(new Homework(), request);
        hw.setIsActive(true);
        if (hw.getAssignedDate() == null) hw.setAssignedDate(LocalDate.now());
        return toResponse(homeworkRepository.save(hw));
    }

    @Transactional
    public HomeworkResponse updateHomework(Long id, HomeworkRequest request) {
        Homework hw = findById(id);
        buildHomework(hw, request);
        return toResponse(homeworkRepository.save(hw));
    }

    @Transactional
    public void deleteHomework(Long id) {
        Homework hw = findById(id);
        hw.setIsActive(false);
        homeworkRepository.save(hw);
    }

    @Transactional(readOnly = true)
    public List<HomeworkSubmissionResponse> getSubmissions(Long homeworkId) {
        return submissionRepository.findByHomeworkId(homeworkId).stream()
            .map(this::toSubmissionResponse).collect(Collectors.toList());
    }

    @Transactional
    public HomeworkSubmissionResponse addSubmission(Long homeworkId, HomeworkSubmissionRequest request) {
        Homework hw = findById(homeworkId);

        if (submissionRepository.findByHomeworkIdAndStudentId(homeworkId, request.getStudentId()).isPresent()) {
            throw new DuplicateResourceException("Submission already exists for this student");
        }

        Student student = studentRepository.findById(request.getStudentId())
            .orElseThrow(() -> new ResourceNotFoundException("Student", request.getStudentId()));

        HomeworkSubmission sub = HomeworkSubmission.builder()
            .homework(hw).student(student)
            .submittedAt(request.getSubmittedAt())
            .fileUrl(request.getFileUrl())
            .remarks(request.getRemarks())
            .marksObtained(request.getMarksObtained())
            .status(request.getStatus() != null ? request.getStatus() : "SUBMITTED")
            .build();

        return toSubmissionResponse(submissionRepository.save(sub));
    }

    @Transactional
    public HomeworkSubmissionResponse updateSubmission(Long submissionId, HomeworkSubmissionRequest request) {
        HomeworkSubmission sub = submissionRepository.findById(submissionId)
            .orElseThrow(() -> new ResourceNotFoundException("HomeworkSubmission", submissionId));
        sub.setMarksObtained(request.getMarksObtained());
        sub.setRemarks(request.getRemarks());
        if (request.getStatus() != null) sub.setStatus(request.getStatus());
        return toSubmissionResponse(submissionRepository.save(sub));
    }

    public Homework findById(Long id) {
        return homeworkRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Homework", id));
    }

    private Homework buildHomework(Homework hw, HomeworkRequest req) {
        hw.setTitle(req.getTitle());
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
        return hw;
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
            .marks(hw.getMarks()).isActive(hw.getIsActive()).createdAt(hw.getCreatedAt()).build();
    }

    private HomeworkSubmissionResponse toSubmissionResponse(HomeworkSubmission sub) {
        return HomeworkSubmissionResponse.builder()
            .id(sub.getId())
            .homeworkId(sub.getHomework().getId()).homeworkTitle(sub.getHomework().getTitle())
            .studentId(sub.getStudent().getId())
            .studentName(sub.getStudent().getFirstName() + " " + sub.getStudent().getLastName())
            .submittedAt(sub.getSubmittedAt()).fileUrl(sub.getFileUrl())
            .remarks(sub.getRemarks()).marksObtained(sub.getMarksObtained())
            .status(sub.getStatus()).createdAt(sub.getCreatedAt()).build();
    }
}
