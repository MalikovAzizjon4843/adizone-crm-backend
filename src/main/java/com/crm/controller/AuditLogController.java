package com.crm.controller;

import com.crm.dto.response.ApiResponse;
import com.crm.dto.response.AuditLogResponse;
import com.crm.dto.response.PageResponse;
import com.crm.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Audit izlarini o'qish. Yozish avtomatik — {@code com.crm.audit.AuditAspect}.
 *
 * <p>To'liq ro'yxat faqat SUPER_ADMIN uchun: unda barcha xodimlarning amallari
 * ko'rinadi. Bitta obyekt tarixini ADMIN ham ko'ra oladi.
 */
@RestController
@RequestMapping("/api/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;

    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<AuditLogResponse>>> search(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) Long entityId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResponse.success(auditLogService.search(
            from, to, userId, action, entityType, entityId, q, page, size)));
    }

    /** Bitta obyekt tarixi, masalan /entity/Student/16. */
    @GetMapping("/entity/{entityType}/{entityId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<List<AuditLogResponse>>> getEntityHistory(
            @PathVariable String entityType,
            @PathVariable Long entityId) {
        return ResponseEntity.ok(ApiResponse.success(
            auditLogService.getEntityHistory(entityType, entityId)));
    }

    @GetMapping("/filters")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getFilters() {
        return ResponseEntity.ok(ApiResponse.success(auditLogService.getFilterOptions()));
    }
}
