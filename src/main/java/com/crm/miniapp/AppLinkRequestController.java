package com.crm.miniapp;

import com.crm.dto.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * CRM: Mini App qo'lda ulash so'rovlari (telegram-platform §11.1) — SA, A, SALES_HEAD. Admin zanjirida
 * ({@code /api/app/**} emas): xodim JWT.
 */
@RestController
@RequestMapping("/api/app-link-requests")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','SALES_HEAD')")
public class AppLinkRequestController {

    private final MiniAppLinkRequestService service;

    /** {@code status}: PENDING (standart) | APPROVED | REJECTED | CANCELLED | ALL. */
    @GetMapping
    public ApiResponse<List<AppDtos.LinkRequestRow>> list(@RequestParam(required = false) String status) {
        return ApiResponse.success(service.list(status));
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<AppDtos.LinkRequestRow> approve(@PathVariable Long id) {
        return ApiResponse.success(service.approve(id));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<AppDtos.LinkRequestRow> reject(@PathVariable Long id,
                                                      @RequestBody(required = false) AppDtos.RejectRequest request) {
        return ApiResponse.success(service.reject(id, request != null ? request.reason() : null));
    }
}
