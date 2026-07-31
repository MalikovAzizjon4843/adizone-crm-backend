package com.crm.controller;

import com.crm.dto.response.*;
import com.crm.service.AnalyticsService;
import com.crm.service.StaffAnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
public class AnalyticsController {
    private final AnalyticsService analyticsService;
    private final StaffAnalyticsService staffAnalyticsService;

    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<DashboardResponse>> getDashboard() {
        return ResponseEntity.ok(ApiResponse.success(analyticsService.getDashboard()));
    }

    @GetMapping("/revenue")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getRevenue(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) Integer count,
            @RequestParam(required = false) Integer months) {
        if (months != null && (period == null || period.isBlank()) && count == null) {
            return ResponseEntity.ok(ApiResponse.success(
                analyticsService.getRevenueAnalytics("monthly", months)));
        }
        return ResponseEntity.ok(ApiResponse.success(
            analyticsService.getRevenueAnalytics(period, count)));
    }

    @GetMapping("/students")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStudentAnalytics() {
        return ResponseEntity.ok(ApiResponse.success(analyticsService.getStudentAnalytics()));
    }

    @GetMapping({"/marketing/sources", "/marketing-sources"})
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMarketingSources() {
        return ResponseEntity.ok(ApiResponse.success(analyticsService.getMarketingSources()));
    }

    @GetMapping("/staff/summary")
    public ResponseEntity<ApiResponse<StaffSummaryResponse>> getStaffSummary(
            @RequestParam(required = false, defaultValue = "monthly") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.success(
            staffAnalyticsService.getStaffSummary(period, from, to)));
    }

    @GetMapping("/staff/{userId}/trend")
    public ResponseEntity<ApiResponse<StaffTrendResponse>> getStaffTrend(
            @PathVariable Long userId,
            @RequestParam(required = false, defaultValue = "monthly") String period,
            @RequestParam(required = false) Integer count,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.success(
            staffAnalyticsService.getStaffTrend(userId, period, count, from, to)));
    }

    @GetMapping("/staff")
    public ResponseEntity<ApiResponse<StaffAnalyticsResponse>> getStaffAnalytics(
            @RequestParam(required = false, defaultValue = "monthly") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.success(
            staffAnalyticsService.getStaffAnalytics(period, from, to)));
    }
}
