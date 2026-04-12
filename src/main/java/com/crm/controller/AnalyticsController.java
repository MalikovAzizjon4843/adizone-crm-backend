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
            @RequestParam(defaultValue = "6") int months) {
        return ResponseEntity.ok(ApiResponse.success(analyticsService.getRevenueAnalytics(months)));
    }

    @GetMapping("/students")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStudentAnalytics() {
        return ResponseEntity.ok(ApiResponse.success(analyticsService.getStudentAnalytics()));
    }

    @GetMapping("/marketing/sources")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMarketingSources() {
        return ResponseEntity.ok(ApiResponse.success(analyticsService.getMarketingSources()));
    }
}
