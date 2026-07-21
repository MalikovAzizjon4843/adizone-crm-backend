package com.crm.controller;

import com.crm.dto.request.LeadAssignRequest;
import com.crm.dto.request.LeadCommentRequest;
import com.crm.dto.request.LeadRequest;
import com.crm.dto.request.LeadStatusRequest;
import com.crm.dto.response.ApiResponse;
import com.crm.dto.response.LeadCommentResponse;
import com.crm.dto.response.LeadOperatorResponse;
import com.crm.dto.response.LeadResponse;
import com.crm.dto.response.LeadStatsResponse;
import com.crm.dto.response.PageResponse;
import com.crm.service.LeadService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/leads")
@RequiredArgsConstructor
public class LeadController {

    private final LeadService leadService;

    @PostMapping("/public")
    public ResponseEntity<ApiResponse<LeadResponse>> createPublicLead(
            @Valid @RequestBody LeadRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        "Ariza qabul qilindi", leadService.createLead(request)));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<LeadResponse>>> getAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long assignedUserId,
            @RequestParam(required = false) Boolean unassigned) {
        return ResponseEntity.ok(ApiResponse.success(
                leadService.getAll(page, size, status, search, assignedUserId, unassigned)));
    }

    @GetMapping("/stats")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<LeadStatsResponse>> getStats() {
        return ResponseEntity.ok(ApiResponse.success(leadService.getStats()));
    }

    @GetMapping("/operators")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<List<LeadOperatorResponse>>> getOperators() {
        return ResponseEntity.ok(ApiResponse.success(leadService.getOperators()));
    }

    @GetMapping("/{id:\\d+}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<LeadResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(leadService.getById(id)));
    }

    @PatchMapping("/{id:\\d+}/assign")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<LeadResponse>> assignLead(
            @PathVariable Long id,
            @RequestBody LeadAssignRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Operator biriktirildi",
                leadService.assignLead(id, request)));
    }

    @PatchMapping("/{id:\\d+}/status")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<LeadResponse>> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody LeadStatusRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Status yangilandi",
                leadService.updateStatus(id, request.getStatus())));
    }

    @PostMapping("/{id:\\d+}/comments")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<LeadCommentResponse>> addComment(
            @PathVariable Long id,
            @Valid @RequestBody LeadCommentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                "Izoh qo'shildi",
                leadService.addComment(id, request)));
    }

    @GetMapping("/{id:\\d+}/comments")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<LeadCommentResponse>>> getComments(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(leadService.getComments(id, page, size)));
    }

    @PostMapping("/{id:\\d+}/convert")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<LeadResponse>> convertToStudent(
            @PathVariable Long id,
            @RequestParam(required = false) Long groupId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                "O'quvchiga o'tkazildi",
                leadService.convertToStudent(id, groupId)));
    }
}
