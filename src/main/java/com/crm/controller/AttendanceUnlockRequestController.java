package com.crm.controller;

import com.crm.dto.request.AttendanceUnlockApproveDto;
import com.crm.dto.request.AttendanceUnlockCreateDto;
import com.crm.dto.response.ApiResponse;
import com.crm.dto.response.AttendanceUnlockResponseDto;
import com.crm.service.AttendanceUnlockRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
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

    /** status berilmasa PENDING; groupId va date ixtiyoriy. */
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<List<AttendanceUnlockResponseDto>>> getRequests(
            @RequestParam(name = "status", required = false, defaultValue = "PENDING") String status,
            @RequestParam(name = "groupId", required = false) Long groupId,
            @RequestParam(name = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(ApiResponse.success(
            service.getRequests(status, groupId, date)));
    }

    /**
     * groupId va date ixtiyoriy. Ikkalasi ham berilmasa avvalgidek barcha
     * so'rovlar qaytadi — eski frontend buzilmaydi. Davomat sahifasi esa
     * aynan o'sha guruh va kunni so'rashi kerak: boshqa kunga berilgan
     * tasdiqlangan ruxsat bugungi jurnalni ochib yubormasin.
     */
    @GetMapping("/my")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<ApiResponse<List<AttendanceUnlockResponseDto>>> getMyRequests(
            @RequestParam(name = "groupId", required = false) Long groupId,
            @RequestParam(name = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(ApiResponse.success(service.getMyRequests(groupId, date)));
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
