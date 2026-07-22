package com.crm.controller;
import com.crm.dto.response.*;
import com.crm.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
public class AnalyticsController {
    private final AnalyticsService analyticsService;

    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<DashboardResponse>> getDashboard() {
        return ResponseEntity.ok(ApiResponse.success(analyticsService.getDashboard()));
    }

    @GetMapping("/revenue")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getRevenue(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) Integer count,
            @RequestParam(required = false) Integer months) {
        // Backward compatibility: ?months=6 implies monthly revenue for 6 months.
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
}
