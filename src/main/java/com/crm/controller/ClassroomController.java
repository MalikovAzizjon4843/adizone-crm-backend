package com.crm.controller;

import com.crm.dto.request.ClassroomRequest;
import com.crm.dto.response.ApiResponse;
import com.crm.dto.response.ClassroomResponse;
import com.crm.service.ClassroomService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/classrooms")
@RequiredArgsConstructor
public class ClassroomController {

    private final ClassroomService classroomService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<ClassroomResponse>>> getAll() {
        return ResponseEntity.ok(ApiResponse.success(classroomService.getAll()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<ClassroomResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(classroomService.getById(id)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<ClassroomResponse>> create(@Valid @RequestBody ClassroomRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success(
                "Xona qo'shildi",
                classroomService.create(req)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<ClassroomResponse>> update(
            @PathVariable Long id,
            @RequestBody ClassroomRequest req) {
        return ResponseEntity.ok(ApiResponse.success(
            "Xona yangilandi",
            classroomService.update(id, req)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        classroomService.delete(id);
        return ResponseEntity.ok(ApiResponse.success("Xona o'chirildi", null));
    }
}
