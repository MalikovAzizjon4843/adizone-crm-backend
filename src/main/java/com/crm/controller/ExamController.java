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
import java.util.Map;

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

    @PostMapping("/{id}/register-student")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEACHER')")
    public ResponseEntity<ApiResponse<ExamRegistrationResponse>> registerStudent(
            @PathVariable Long id,
            @RequestParam Long studentId) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Ro'yxatdan o'tdi",
                examService.registerStudentForExam(id, studentId)));
    }

    @GetMapping("/{id}/eligible-students")
    public ResponseEntity<ApiResponse<List<StudentResponse>>> getEligibleStudents(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(examService.getEligibleStudents(id)));
    }

    @PostMapping("/{id}/calculate-payment")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> calculatePayment(
            @PathVariable Long id,
            @RequestParam Long studentId) {
        return ResponseEntity.ok(ApiResponse.success(
            examService.calculateExamPaymentPreview(id, studentId)));
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
