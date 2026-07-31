package com.crm.controller;

import com.crm.dto.request.NoticeRequest;
import com.crm.dto.response.*;
import com.crm.service.NoticeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notices")
@RequiredArgsConstructor
public class NoticeController {

    private final NoticeService noticeService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<PageResponse<NoticeResponse>>> getAllNotices(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(noticeService.getAllNotices(page, size)));
    }

    /** Bell feed: active (published + non-expired) notices with isRead. */
    @GetMapping("/active")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<NoticeResponse>>> getActive(
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(ApiResponse.success(noticeService.getLatestNotices(limit)));
    }

    @GetMapping("/latest")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<NoticeResponse>>> getLatest(
            @RequestParam(defaultValue = "5") int limit) {
        return ResponseEntity.ok(ApiResponse.success(noticeService.getLatestNotices(limit)));
    }

    @GetMapping("/unread-count")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getUnreadCount() {
        return ResponseEntity.ok(ApiResponse.success(
            Map.of("count", noticeService.getUnreadCount())));
    }

    @PostMapping("/read-all")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Void>> markAllRead() {
        noticeService.markAllRead();
        return ResponseEntity.ok(ApiResponse.success("All notices marked as read", null));
    }

    @PostMapping("/{id:\\d+}/read")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Void>> markRead(@PathVariable Long id) {
        noticeService.markRead(id);
        return ResponseEntity.ok(ApiResponse.success("Notice marked as read", null));
    }

    @GetMapping("/{id:\\d+}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<NoticeResponse>> getNoticeById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(noticeService.getNoticeById(id)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<NoticeResponse>> createNotice(@Valid @RequestBody NoticeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Notice created", noticeService.createNotice(request)));
    }

    @PutMapping("/{id:\\d+}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<NoticeResponse>> updateNotice(
            @PathVariable Long id, @Valid @RequestBody NoticeRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Notice updated", noticeService.updateNotice(id, request)));
    }

    @DeleteMapping("/{id:\\d+}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteNotice(@PathVariable Long id) {
        noticeService.deleteNotice(id);
        return ResponseEntity.ok(ApiResponse.success("Notice unpublished", null));
    }
}
