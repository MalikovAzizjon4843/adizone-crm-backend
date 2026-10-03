package com.crm.controller;

import com.crm.dto.response.AbsenceNoticeResponse;
import com.crm.dto.response.ApiResponse;
import com.crm.service.AbsenceNoticeService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Sabab bildirishlar (telegram-platform §11.2) — CRM: SA, A va o'qituvchi (o'z guruhi yoki shu kungi o'rinbosar,
 * {@code AttendanceAccessService.assertCanRead}). Faqat faol (bekor qilinmagan) bildirishlar.
 */
@RestController
@RequestMapping("/api/absence-notices")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEACHER')")
public class AbsenceNoticeController {

    private final AbsenceNoticeService service;
    private final Clock billingClock;

    @GetMapping
    public ApiResponse<List<AbsenceNoticeResponse>> list(
            @RequestParam Long groupId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.success(service.forGroup(groupId, date != null ? date : LocalDate.now(billingClock)));
    }
}
