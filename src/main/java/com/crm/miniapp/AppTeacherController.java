package com.crm.miniapp;

import com.crm.dto.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Mini App o'qituvchi rejimi (telegram-platform §11.4) — {@code ROLE_APP_TEACHER} ({@link AppSecurityConfig}).
 * Chatlar — umumiy {@code /api/app/chats} ({@code side: STAFF}).
 */
@RestController
@RequestMapping("/api/app/teacher")
@RequiredArgsConstructor
public class AppTeacherController {

    private final AppTeacherService service;

    @GetMapping("/today")
    public ApiResponse<AppDtos.TeacherDay> today(
            @AuthenticationPrincipal AppPrincipal principal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.success(service.today(principal, date));
    }

    @GetMapping("/attendance/{groupId}")
    public ApiResponse<AppDtos.TeacherAttendance> attendance(
            @AuthenticationPrincipal AppPrincipal principal, @PathVariable Long groupId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.success(service.attendance(principal, groupId, date));
    }

    @PutMapping("/attendance/{groupId}")
    public ApiResponse<AppDtos.TeacherAttendance> save(
            @AuthenticationPrincipal AppPrincipal principal, @PathVariable Long groupId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Valid @RequestBody AppDtos.TeacherAttendanceSave request) {
        return ApiResponse.success("Davomat saqlandi", service.save(principal, groupId, date, request));
    }

    @PostMapping("/unlock-requests")
    public ApiResponse<AppDtos.UnlockInfo> requestUnlock(@AuthenticationPrincipal AppPrincipal principal,
                                                         @Valid @RequestBody AppDtos.UnlockCreate request) {
        return ApiResponse.success("So'rov yuborildi", service.requestUnlock(principal, request));
    }

    @GetMapping("/unlock-requests")
    public ApiResponse<List<AppDtos.UnlockInfo>> unlockRequests(
            @AuthenticationPrincipal AppPrincipal principal,
            @RequestParam(required = false) Long groupId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.success(service.unlockRequests(principal, groupId, date));
    }
}
