package com.crm.controller;

import com.crm.dto.response.ApiResponse;
import com.crm.service.TeacherService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/repair")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminRepairController {

    private final TeacherService teacherService;

    @PostMapping("/link-teacher-users")
    public ResponseEntity<ApiResponse<Map<String, Object>>> linkTeacherUsers() {
        return ResponseEntity.ok(ApiResponse.success(
            "O'qituvchi-user bog'lanishi yangilandi",
            teacherService.linkTeacherUsers()));
    }
}
