package com.crm.controller;

import com.crm.dto.request.HomeworkRequest;
import com.crm.dto.request.HomeworkSubmissionRequest;
import com.crm.dto.response.*;
import com.crm.service.HomeworkService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/homework")
@RequiredArgsConstructor
public class HomeworkController {

    private final HomeworkService homeworkService;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<HomeworkResponse>>> getAllHomeworks(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(homeworkService.getAllHomeworks(page, size)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<HomeworkResponse>> getHomeworkById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(homeworkService.getHomeworkById(id)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEACHER')")
    public ResponseEntity<ApiResponse<HomeworkResponse>> createHomework(@Valid @RequestBody HomeworkRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Homework created", homeworkService.createHomework(request)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEACHER')")
    public ResponseEntity<ApiResponse<HomeworkResponse>> updateHomework(
            @PathVariable Long id, @Valid @RequestBody HomeworkRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Homework updated", homeworkService.updateHomework(id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteHomework(@PathVariable Long id) {
        homeworkService.deleteHomework(id);
        return ResponseEntity.ok(ApiResponse.success("Homework deleted", null));
    }

    @GetMapping("/{id}/submissions")
    public ResponseEntity<ApiResponse<List<HomeworkSubmissionResponse>>> getSubmissions(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(homeworkService.getSubmissions(id)));
    }

    @PostMapping("/{id}/submissions")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<HomeworkSubmissionResponse>> addSubmission(
            @PathVariable Long id, @Valid @RequestBody HomeworkSubmissionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Submission added", homeworkService.addSubmission(id, request)));
    }

    @PutMapping("/submissions/{submissionId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEACHER')")
    public ResponseEntity<ApiResponse<HomeworkSubmissionResponse>> updateSubmission(
            @PathVariable Long submissionId, @Valid @RequestBody HomeworkSubmissionRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Submission updated",
            homeworkService.updateSubmission(submissionId, request)));
    }
}
