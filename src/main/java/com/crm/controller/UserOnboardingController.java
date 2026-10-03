package com.crm.controller;

import com.crm.dto.response.ApiResponse;
import com.crm.dto.response.OnboardingItemResponse;
import com.crm.service.UserOnboardingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Joriy xodimning onboarding holati (frontend turlari). Barcha STAFF rollar (SecurityConfig), faqat
 * o'z yozuvlari — boshqa foydalanuvchi id si qabul qilinmaydi.
 */
@RestController
@RequestMapping("/api/users/me/onboarding")
@RequiredArgsConstructor
public class UserOnboardingController {

    private final UserOnboardingService onboardingService;

    /** Ko'rilgan turlar, kalit bo'yicha tartiblangan. */
    @GetMapping
    public ApiResponse<List<OnboardingItemResponse>> list() {
        return ApiResponse.success(onboardingService.list());
    }

    /** Turni "ko'rildi" deb belgilash (idempotent). */
    @PutMapping("/{key}")
    public ApiResponse<OnboardingItemResponse> markSeen(@PathVariable String key) {
        return ApiResponse.success(onboardingService.markSeen(key));
    }

    /** Hammasini tozalash — "turlarni qayta ko'rish". */
    @DeleteMapping
    public ApiResponse<Map<String, Integer>> reset() {
        return ApiResponse.success(Map.of("deleted", onboardingService.reset()));
    }
}
