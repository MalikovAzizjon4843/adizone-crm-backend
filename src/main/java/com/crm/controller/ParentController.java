package com.crm.controller;

import com.crm.dto.request.ParentRequest;
import com.crm.dto.response.*;
import com.crm.service.ParentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/parents")
@RequiredArgsConstructor
public class ParentController {

    private final ParentService parentService;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<ParentResponse>>> getAllParents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(parentService.getAllParents(page, size)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ParentResponse>> getParentById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(parentService.getParentById(id)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<ParentResponse>> createParent(@Valid @RequestBody ParentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Parent created", parentService.createParent(request)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<ParentResponse>> updateParent(
            @PathVariable Long id, @Valid @RequestBody ParentRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Parent updated", parentService.updateParent(id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteParent(@PathVariable Long id) {
        parentService.deleteParent(id);
        return ResponseEntity.ok(ApiResponse.success("Parent deactivated", null));
    }

    @GetMapping("/student/{studentId}")
    public ResponseEntity<ApiResponse<List<ParentResponse>>> getParentsByStudent(@PathVariable Long studentId) {
        return ResponseEntity.ok(ApiResponse.success(parentService.getParentsByStudent(studentId)));
    }

    @PostMapping("/{id}/link/{studentId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<Void>> linkToStudentByPath(
            @PathVariable Long id, @PathVariable Long studentId) {
        parentService.linkParentToStudent(id, Map.of("studentId", studentId));
        return ResponseEntity.ok(ApiResponse.success("Parent linked to student", null));
    }

    @PostMapping("/{id}/students")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<Void>> linkToStudent(
            @PathVariable Long id, @RequestBody Map<String, Object> body) {
        parentService.linkParentToStudent(id, body);
        return ResponseEntity.ok(ApiResponse.success("Parent linked to student", null));
    }

    @DeleteMapping("/{id}/students/{studentId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<Void>> unlinkFromStudent(
            @PathVariable Long id, @PathVariable Long studentId) {
        parentService.unlinkParentFromStudent(id, studentId);
        return ResponseEntity.ok(ApiResponse.success("Parent unlinked from student", null));
    }
}
