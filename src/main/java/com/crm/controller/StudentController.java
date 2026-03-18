package com.crm.controller;

import com.crm.dto.request.StudentRequest;
import com.crm.dto.response.*;
import com.crm.entity.enums.StudentStatus;
import com.crm.service.StudentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/students")
@RequiredArgsConstructor
public class StudentController {

    private final StudentService studentService;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<StudentResponse>>> getAllStudents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) StudentStatus status) {
        return ResponseEntity.ok(ApiResponse.success(
            studentService.getAllStudents(page, size, search, status)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<StudentDetailResponse>> getStudentById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(studentService.getStudentById(id)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<StudentResponse>> createStudent(
            @Valid @RequestBody StudentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Student created", studentService.createStudent(request)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<StudentResponse>> updateStudent(
            @PathVariable Long id, @Valid @RequestBody StudentRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Student updated", studentService.updateStudent(id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteStudent(@PathVariable Long id) {
        studentService.deleteStudent(id);
        return ResponseEntity.ok(ApiResponse.success("Student deactivated", null));
    }
}
