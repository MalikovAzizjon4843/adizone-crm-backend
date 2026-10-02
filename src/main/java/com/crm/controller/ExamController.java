package com.crm.controller;

import com.crm.dto.request.ExamRegistrationCancelRequest;
import com.crm.dto.request.ExamRegistrationRequest;
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
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEACHER')")
    public ResponseEntity<ApiResponse<PageResponse<ExamResponse>>> getAllExams(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(examService.getAllExams(page, size)));
    }

    /** Yozilishlar ro'yxati (E-04): TEACHER — faqat o'z imtihoni. */
    @GetMapping("/{id}/registrations")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT','TEACHER')")
    public ResponseEntity<ApiResponse<PageResponse<ExamRegistrationResponse>>> getRegistrations(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResponse.success(examService.getRegistrations(id, page, size)));
    }

    /**
     * Yozilish (leaves-exams-contracts §4.2). Pullik imtihonda to'lov kassaga shu so'rovda tushadi —
     * faqat SA/A/ACC; TEACHER — faqat bepul imtihonga. {@code Idempotency-Key} — takror bosish ikkinchi
     * kirim yozmaydi: o'sha yozilish 200 + {@code X-Idempotent-Replay: true}.
     */
    @PostMapping("/{id}/registrations")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT','TEACHER')")
    public ResponseEntity<ApiResponse<ExamRegistrationResponse>> register(
            @PathVariable Long id,
            @Valid @RequestBody ExamRegistrationRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        ExamRegistrationResponse r = examService.register(id, request, idempotencyKey);
        if (Boolean.TRUE.equals(r.getIdempotentReplay())) {
            return ResponseEntity.ok().header("X-Idempotent-Replay", "true")
                .body(ApiResponse.success("Ro'yxatdan o'tgan", r));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Ro'yxatdan o'tdi", r));
    }

    /** Bekor qilish (§4.3): PAID bo'lsa kassaga REVERSAL. */
    @PostMapping("/{id}/registrations/{regId}/cancel")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT')")
    public ResponseEntity<ApiResponse<ExamRegistrationResponse>> cancelRegistration(
            @PathVariable Long id,
            @PathVariable Long regId,
            @RequestBody(required = false) ExamRegistrationCancelRequest body) {
        return ResponseEntity.ok(ApiResponse.success("Yozilish bekor qilindi",
            examService.cancelRegistration(id, regId, body != null ? body.getReason() : null)));
    }

    /** @deprecated {@code POST /{id}/registrations}; faqat bepul imtihonda ishlaydi. */
    @Deprecated
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
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEACHER')")
    public ResponseEntity<ApiResponse<List<StudentResponse>>> getEligibleStudents(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(examService.getEligibleStudents(id)));
    }

    /** @deprecated Endi faqat imtihon narxini qaytaradi (eski "to'lanmagan kunlar" preview olib tashlandi). */
    @Deprecated
    @PostMapping("/{id}/calculate-payment")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEACHER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> calculatePayment(
            @PathVariable Long id,
            @RequestParam Long studentId) {
        return ResponseEntity.ok(ApiResponse.success(
            examService.calculateExamPaymentPreview(id, studentId)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEACHER')")
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
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEACHER')")
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

    @PutMapping("/{examId}/results/{resultId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEACHER')")
    public ResponseEntity<ApiResponse<ExamResultResponse>> updateResult(
            @PathVariable Long examId,
            @PathVariable Long resultId,
            @RequestBody ExamResultRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Result updated",
            examService.updateResult(examId, resultId, request)));
    }

    /** @deprecated use PUT /{examId}/results/{resultId} */
    @PutMapping("/results/{resultId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEACHER')")
    public ResponseEntity<ApiResponse<ExamResultResponse>> updateResultLegacy(
            @PathVariable Long resultId, @RequestBody ExamResultRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Result updated", examService.updateResult(resultId, request)));
    }

    @GetMapping("/students/{studentId}/results")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEACHER')")
    public ResponseEntity<ApiResponse<List<ExamResultResponse>>> getResultsByStudent(@PathVariable Long studentId) {
        return ResponseEntity.ok(ApiResponse.success(examService.getResultsByStudent(studentId)));
    }
}
