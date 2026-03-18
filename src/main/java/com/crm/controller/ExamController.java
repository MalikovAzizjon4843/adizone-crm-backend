package com.crm.controller;

import com.crm.dto.request.ExamRequest;
import com.crm.dto.request.ExamResultRequest;
import com.crm.dto.response.*;
import com.crm.service.ExamService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/exams")
@RequiredArgsConstructor
public class ExamController {

    private final ExamService examService;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<ExamResponse>>> getAllExams(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(examService.getAllExams(page, size)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ExamResponse>> getExamById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(examService.getExamById(id)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEACHER')")
    public ResponseEntity<ApiResponse<ExamResponse>> createExam(@Valid @RequestBody ExamRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Exam created", examService.createExam(request)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEACHER')")
    public ResponseEntity<ApiResponse<ExamResponse>> updateExam(
            @PathVariable Long id, @Valid @RequestBody ExamRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Exam updated", examService.updateExam(id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteExam(@PathVariable Long id) {
        examService.deleteExam(id);
        return ResponseEntity.ok(ApiResponse.success("Exam deleted", null));
    }

    @GetMapping("/{id}/results")
    public ResponseEntity<ApiResponse<List<ExamResultResponse>>> getResultsByExam(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(examService.getResultsByExam(id)));
    }

    @PostMapping("/{id}/results")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEACHER')")
    public ResponseEntity<ApiResponse<ExamResultResponse>> addResult(
            @PathVariable Long id, @Valid @RequestBody ExamResultRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Result added", examService.addResult(id, request)));
    }

    @PutMapping("/results/{resultId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEACHER')")
    public ResponseEntity<ApiResponse<ExamResultResponse>> updateResult(
            @PathVariable Long resultId, @Valid @RequestBody ExamResultRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Result updated", examService.updateResult(resultId, request)));
    }

    @GetMapping("/students/{studentId}/results")
    public ResponseEntity<ApiResponse<List<ExamResultResponse>>> getResultsByStudent(@PathVariable Long studentId) {
        return ResponseEntity.ok(ApiResponse.success(examService.getResultsByStudent(studentId)));
    }
}
