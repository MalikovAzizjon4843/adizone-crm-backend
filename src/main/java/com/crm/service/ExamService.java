package com.crm.service;

import com.crm.dto.request.ExamRequest;
import com.crm.dto.request.ExamResultRequest;
import com.crm.dto.response.*;
import com.crm.entity.*;
import com.crm.exception.DuplicateResourceException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ExamService {

    private final ExamRepository examRepository;
    private final ExamResultRepository examResultRepository;
    private final StudentRepository studentRepository;
    private final ClassRepository classRepository;
    private final SubjectRepository subjectRepository;

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

    private Exam buildExam(Exam e, ExamRequest req) {
        e.setExamName(req.getExamName());
        e.setExamType(req.getExamType());
        e.setExamDate(req.getExamDate());
        e.setStartTime(req.getStartTime());
        e.setEndTime(req.getEndTime());
        e.setTotalMarks(req.getTotalMarks());
        e.setPassMarks(req.getPassMarks());
        e.setAcademicYear(req.getAcademicYear());
        if (req.getClassId() != null) {
            e.setClassEntity(classRepository.findById(req.getClassId())
                .orElseThrow(() -> new ResourceNotFoundException("Class", req.getClassId())));
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
        return ExamResponse.builder()
            .id(e.getId()).uuid(e.getUuid()).examName(e.getExamName()).examType(e.getExamType())
            .classId(e.getClassEntity() != null ? e.getClassEntity().getId() : null)
            .className(e.getClassEntity() != null ? e.getClassEntity().getClassName() : null)
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
