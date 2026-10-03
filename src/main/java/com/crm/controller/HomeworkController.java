package com.crm.controller;

import com.crm.dto.request.HomeworkGradeRequest;
import com.crm.dto.request.HomeworkRequest;
import com.crm.dto.request.HomeworkSubmissionRequest;
import com.crm.dto.response.*;
import com.crm.service.HomeworkService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** Uy vazifalari (phase6-api §4): SA, A — hammasi; T — faqat o'z guruhlari. */
@RestController
@RequestMapping("/api/homework")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEACHER')")
public class HomeworkController {

    private final HomeworkService homeworkService;

    /** Faol vazifalar, muddat ↓. {@code from}/{@code to} — topshirish muddati. */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<HomeworkResponse>>> getAllHomeworks(
            @RequestParam(required = false) Long groupId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(
            homeworkService.getAllHomeworks(new HomeworkService.Filter(groupId, from, to), page, size)));
    }

    /** @deprecated {@code GET /api/homework?groupId=}. */
    @Deprecated
    @GetMapping("/group/{groupId}")
    public ResponseEntity<ApiResponse<PageResponse<HomeworkResponse>>> getHomeworkByGroup(
            @PathVariable Long groupId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResponse.success(homeworkService.getHomeworksByGroup(groupId, page, size)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<HomeworkResponse>> getHomeworkById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(homeworkService.getHomeworkById(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<HomeworkResponse>> createHomework(@Valid @RequestBody HomeworkRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Uy vazifasi yaratildi", homeworkService.createHomework(request)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<HomeworkResponse>> updateHomework(
            @PathVariable Long id, @Valid @RequestBody HomeworkRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Uy vazifasi yangilandi", homeworkService.updateHomework(id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteHomework(@PathVariable Long id) {
        homeworkService.deleteHomework(id);
        return ResponseEntity.ok(ApiResponse.success("Uy vazifasi o'chirildi", null));
    }

    /** Guruh o'quvchilari bo'yicha holat va xulosa. */
    @GetMapping("/{id}/students")
    public ResponseEntity<ApiResponse<HomeworkRosterResponse>> roster(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(homeworkService.roster(id)));
    }

    /** Holat/baho/izohni belgilash (upsert, ko'plikda) — hammasi yoki hech biri; javob — yangilangan ro'yxat. */
    @PutMapping("/{id}/students")
    public ResponseEntity<ApiResponse<HomeworkRosterResponse>> grade(
            @PathVariable Long id, @Valid @RequestBody HomeworkGradeRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Saqlandi", homeworkService.grade(id, request)));
    }

    /** @deprecated {@code GET /{id}/students}. */
    @Deprecated
    @GetMapping("/{id}/submissions")
    public ResponseEntity<ApiResponse<List<HomeworkSubmissionResponse>>> getSubmissions(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(homeworkService.getSubmissions(id)));
    }

    /** @deprecated {@code PUT /{id}/students}. */
    @Deprecated
    @PostMapping("/{id}/submissions")
    public ResponseEntity<ApiResponse<HomeworkSubmissionResponse>> addSubmission(
            @PathVariable Long id, @Valid @RequestBody HomeworkSubmissionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Submission added", homeworkService.addSubmission(id, request)));
    }

    /** @deprecated {@code PUT /{id}/students}. */
    @Deprecated
    @PutMapping("/submissions/{submissionId}")
    public ResponseEntity<ApiResponse<HomeworkSubmissionResponse>> updateSubmission(
            @PathVariable Long submissionId, @Valid @RequestBody HomeworkSubmissionRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Submission updated",
            homeworkService.updateSubmission(submissionId, request)));
    }
}
