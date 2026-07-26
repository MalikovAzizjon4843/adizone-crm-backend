package com.crm.controller;

import com.crm.dto.request.AttendanceUnlockApproveDto;
import com.crm.dto.request.AttendanceUnlockCreateDto;
import com.crm.dto.response.ApiResponse;
import com.crm.dto.response.AttendanceUnlockResponseDto;
import com.crm.service.AttendanceUnlockRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/attendance/unlock-requests")
@RequiredArgsConstructor
public class AttendanceUnlockRequestController {

    private final AttendanceUnlockRequestService service;

    @PostMapping
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<ApiResponse<AttendanceUnlockResponseDto>> createRequest(
            @Valid @RequestBody AttendanceUnlockCreateDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Unlock request submitted", service.createRequest(dto)));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<List<AttendanceUnlockResponseDto>>> getPendingRequests(
            @RequestParam(required = false, defaultValue = "PENDING") String status) {
        // As requested: "Admin uchun: barcha PENDING requestlar"
        return ResponseEntity.ok(ApiResponse.success(service.getPendingRequests()));
    }

    @GetMapping("/my")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<ApiResponse<List<AttendanceUnlockResponseDto>>> getMyRequests() {
        return ResponseEntity.ok(ApiResponse.success(service.getMyRequests()));
    }

    @PatchMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<AttendanceUnlockResponseDto>> approveRequest(
            @PathVariable Long id,
            @RequestBody(required = false) AttendanceUnlockApproveDto dto) {
        return ResponseEntity.ok(ApiResponse.success("Request approved", service.approveRequest(id, dto)));
    }

    @PatchMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<AttendanceUnlockResponseDto>> rejectRequest(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Request rejected", service.rejectRequest(id)));
    }

    @GetMapping("/count")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<Long>> getPendingCount() {
        return ResponseEntity.ok(ApiResponse.success(service.getPendingRequestsCount()));
    }
}
