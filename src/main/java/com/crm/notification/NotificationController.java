package com.crm.notification;

import com.crm.dto.response.ApiResponse;
import com.crm.dto.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * CRM qo'ng'iroqchasi — barcha xodimlar (SecurityConfig: STAFF), faqat o'z bildirishnomalari.
 * Real vaqt: STOMP {@code /user/queue/notifications}.
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService service;

    /** Yangilari oldin; {@code size} 1..100 (standart 20). */
    @GetMapping("/me")
    public ApiResponse<PageResponse<UserNotificationResponse>> mine(@RequestParam(defaultValue = "0") int page,
                                                                   @RequestParam(defaultValue = "20") int size) {
        Page<UserNotificationResponse> p = service.mine(page, size);
        return ApiResponse.success(PageResponse.<UserNotificationResponse>builder()
            .content(p.getContent())
            .pageNumber(p.getNumber())
            .pageSize(p.getSize())
            .totalElements(p.getTotalElements())
            .totalPages(p.getTotalPages())
            .last(p.isLast())
            .build());
    }

    @GetMapping("/me/unread-count")
    public ApiResponse<Map<String, Long>> unreadCount() {
        return ApiResponse.success(Map.of("count", service.unreadCount()));
    }

    @PostMapping("/{id}/read")
    public ApiResponse<UserNotificationResponse> read(@PathVariable Long id) {
        return ApiResponse.success(service.markRead(id));
    }

    @PostMapping("/read-all")
    public ApiResponse<Map<String, Integer>> readAll() {
        return ApiResponse.success(Map.of("updated", service.markAllRead()));
    }
}
