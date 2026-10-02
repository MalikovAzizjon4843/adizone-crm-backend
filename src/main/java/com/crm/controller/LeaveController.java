package com.crm.controller;

import com.crm.dto.request.LeaveDecisionRequest;
import com.crm.dto.request.LeaveSubmitRequest;
import com.crm.dto.response.*;
import com.crm.entity.enums.LeaveStatus;
import com.crm.exception.CodedException;
import com.crm.service.LeaveService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Ta'tillar — docs/design/leaves-exams-contracts.md §1.3 (API: leaves-exams-contracts-api.md).
 * Ariza va o'z arizalari — barcha xodimlar; ro'yxat, tasdiqlash/rad — SA, A. Hard delete yo'q.
 */
@RestController
@RequestMapping("/api/leaves")
@RequiredArgsConstructor
public class LeaveController {

    private static final String STAFF =
        "hasAnyRole('SUPER_ADMIN','ADMIN','SALES_MANAGER','ACCOUNTANT','TEACHER')";

    private final LeaveService leaveService;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<LeaveResponse>>> getAllLeaves(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long teacherId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Boolean paid) {
        LeaveStatus st = null;
        if (status != null && !status.isBlank()) {
            st = LeaveStatus.parseOrNull(status);
            if (st == null) {
                throw CodedException.badRequest("leave.status.invalid", status);
            }
        }
        return ResponseEntity.ok(ApiResponse.success(leaveService.search(
            new LeaveService.Filter(st, userId, teacherId, from, to, paid), page, size)));
    }

    /** O'zi haqidagi va o'zi bergan arizalar. */
    @GetMapping("/my")
    @PreAuthorize(STAFF)
    public ResponseEntity<ApiResponse<PageResponse<LeaveResponse>>> my(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(leaveService.my(page, size)));
    }

    @GetMapping("/pending/count")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Long>>> pendingCount() {
        return ResponseEntity.ok(ApiResponse.success(Map.of("count", leaveService.pendingCount())));
    }

    /** SA/A — istalgan xodim; boshqalar — faqat o'zi ({@code userId} berilmasa — o'zi). */
    @GetMapping("/summary")
    @PreAuthorize(STAFF)
    public ResponseEntity<ApiResponse<LeaveSummaryDto>> summary(
            @RequestParam(required = false) Long userId,
            @RequestParam int year) {
        return ResponseEntity.ok(ApiResponse.success(leaveService.summary(userId, year)));
    }

    /** @deprecated {@code GET /api/leaves?status=PENDING}. */
    @Deprecated
    @GetMapping("/pending")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<List<LeaveResponse>>> getPendingLeaves() {
        return ResponseEntity.ok(ApiResponse.success(leaveService.search(
            new LeaveService.Filter(LeaveStatus.PENDING, null, null, null, null, null), 0, 500).getContent()));
    }

    /** @deprecated {@code GET /api/leaves?teacherId=}. */
    @Deprecated
    @GetMapping("/teacher/{teacherId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<LeaveResponse>>> getLeavesByTeacher(
            @PathVariable Long teacherId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResponse.success(leaveService.search(
            new LeaveService.Filter(null, null, teacherId, null, null, null), page, size)));
    }

    /** @deprecated {@code GET /api/leaves/my} yoki {@code GET /api/leaves?userId=}. */
    @Deprecated
    @GetMapping("/user/{userId}")
    @PreAuthorize(STAFF)
    public ResponseEntity<ApiResponse<PageResponse<LeaveResponse>>> getLeavesByUser(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(leaveService.byUser(userId, page, size)));
    }

    /** SA/A yoki ariza egasi (servisda tekshiriladi). */
    @GetMapping("/{id}")
    @PreAuthorize(STAFF)
    public ResponseEntity<ApiResponse<LeaveResponse>> getLeaveById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(leaveService.get(id)));
    }

    @GetMapping("/{id}/affected-lessons")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<List<AffectedLessonDto>>> affectedLessons(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(leaveService.affectedLessons(id)));
    }

    /** Xodim — o'zi uchun; SA/A — {@code userId} (yoki eski {@code teacherId}) bilan istalgan xodim uchun. */
    @PostMapping
    @PreAuthorize(STAFF)
    public ResponseEntity<ApiResponse<LeaveResponse>> submitLeave(@Valid @RequestBody LeaveSubmitRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Leave request submitted", leaveService.submit(request)));
    }

    /** {@code {paid: boolean (majburiy), note?}} — javobda o'qituvchining shu davrdagi darslari. */
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<LeaveResponse>> approve(@PathVariable Long id,
                                                              @RequestBody(required = false) LeaveDecisionRequest body) {
        return ResponseEntity.ok(ApiResponse.success("Ta'til tasdiqlandi", leaveService.approve(id, body)));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<LeaveResponse>> reject(@PathVariable Long id,
                                                             @RequestBody(required = false) LeaveDecisionRequest body) {
        return ResponseEntity.ok(ApiResponse.success("Ta'til rad etildi",
            leaveService.reject(id, body != null ? body.getNote() : null)));
    }

    /** Egasi — o'z PENDING arizasi; SA/A — PENDING yoki APPROVED. */
    @PostMapping("/{id}/cancel")
    @PreAuthorize(STAFF)
    public ResponseEntity<ApiResponse<LeaveResponse>> cancel(@PathVariable Long id,
                                                             @RequestBody(required = false) LeaveDecisionRequest body) {
        return ResponseEntity.ok(ApiResponse.success("Ta'til bekor qilindi",
            leaveService.cancel(id, body != null ? body.getNote() : null)));
    }

    /** @deprecated {@code POST /{id}/approve|reject|cancel}; APPROVED da {@code paid} majburiy. */
    @Deprecated
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<LeaveResponse>> updateStatus(
            @PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success("Leave request updated", leaveService.legacyStatus(id, body)));
    }
}
