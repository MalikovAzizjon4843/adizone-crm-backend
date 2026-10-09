package com.crm.controller;

import com.crm.dashboard.AnalyticsDtos;
import com.crm.dashboard.AnalyticsOverviewService;
import com.crm.dashboard.SourceAnalyticsService;
import com.crm.dashboard.StaffPerformanceService;
import com.crm.dto.response.*;
import com.crm.exception.CodedException;
import com.crm.billing.BillingAuth;
import com.crm.service.AnalyticsService;
import com.crm.service.StaffAnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/**
 * Analitika. Yangi shartnoma — {@code GET /overview} va {@code GET /staff?role=} (docs/design/phase6-api.md §1–§2).
 * Qolgan endpointlar eski frontend uchun qoldirilgan va <b>deprecated</b>: ular butun davr bo'yicha (sanasiz)
 * yoki direktor dashboardidan boshqa ta'rif bilan hisoblaydi — yangi panel ularni ishlatmaydi.
 */
@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
public class AnalyticsController {
    private final AnalyticsService analyticsService;
    private final StaffAnalyticsService staffAnalyticsService;
    private final AnalyticsOverviewService overviewService;
    private final StaffPerformanceService staffPerformanceService;
    private final SourceAnalyticsService sourceAnalyticsService;

    /** Umumiy ko'rinish: moliya, o'quvchilar, lidlar, guruhlar + oldingi teng davr va vaqt qatorlari. */
    @GetMapping("/overview")
    public ResponseEntity<ApiResponse<AnalyticsDtos.Overview>> overview(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String groupBy) {
        return ResponseEntity.ok(ApiResponse.success(overviewService.overview(from, to, groupBy)));
    }

    /**
     * Manba statistikasi: davrda yaratilgan lidlar manba ({@code groupBy=SOURCE}, standart) yoki Meta forma
     * ({@code META_FORM}) bo'yicha — lidlar, tashrif, konvertatsiya, birinchi to'lov, tushum, konversiya %.
     * "Noma'lum" — alohida qator. Sana chegaralari Asia/Tashkent; importlar {@code includeImported=true} bilan.
     */
    @GetMapping("/sources")
    public ResponseEntity<ApiResponse<AnalyticsDtos.SourceReport>> sources(
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "groupBy", required = false) String groupBy,
            @RequestParam(name = "includeImported", required = false) Boolean includeImported) {
        return ResponseEntity.ok(ApiResponse.success(
            sourceAnalyticsService.sources(from, to, groupBy, includeImported)));
    }

    /** Hozirgi faol o'quvchilar (faol yozilmasi bor) {@code students.source} bo'yicha. */
    @GetMapping("/students-by-source")
    public ResponseEntity<ApiResponse<AnalyticsDtos.StudentsBySource>> studentsBySource() {
        return ResponseEntity.ok(ApiResponse.success(sourceAnalyticsService.studentsBySource()));
    }

    /**
     * Xodimlar samaradorligi. {@code role} berilsa — yangi javob ({@link AnalyticsDtos.StaffPerformance});
     * SALES_HEAD — faqat {@code role=SALES}. {@code role} siz — eski javob (deprecated, faqat SA/A).
     */
    @GetMapping("/staff")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','SALES_HEAD')")
    public ResponseEntity<ApiResponse<?>> getStaffAnalytics(
            @RequestParam(required = false) String role,
            @RequestParam(required = false, defaultValue = "monthly") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        boolean head = BillingAuth.hasAnyRole("SALES_HEAD") && !BillingAuth.hasAnyRole("SUPER_ADMIN", "ADMIN");
        if (role == null || role.isBlank()) {
            if (head) {
                throw CodedException.forbidden("analytics.staff.roleForbidden", "-");
            }
            return ResponseEntity.ok(ApiResponse.success(staffAnalyticsService.getStaffAnalytics(period, from, to)));
        }
        AnalyticsDtos.StaffRole r = StaffPerformanceService.parseRole(role);
        if (head && r != AnalyticsDtos.StaffRole.SALES) {
            throw CodedException.forbidden("analytics.staff.roleForbidden", r);
        }
        return ResponseEntity.ok(ApiResponse.success(staffPerformanceService.staff(r, from, to)));
    }

    /** @deprecated {@code GET /overview}. */
    @Deprecated
    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<DashboardResponse>> getDashboard() {
        return ResponseEntity.ok(ApiResponse.success(analyticsService.getDashboard()));
    }

    /** @deprecated {@code GET /overview} ({@code finance.income}, {@code groupBy}). */
    @Deprecated
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

    /** @deprecated {@code GET /overview} ({@code students}). */
    @Deprecated
    @GetMapping("/students")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStudentAnalytics() {
        return ResponseEntity.ok(ApiResponse.success(analyticsService.getStudentAnalytics()));
    }

    /** @deprecated {@code GET /overview} ({@code leads.bySource}). */
    @Deprecated
    @GetMapping({"/marketing/sources", "/marketing-sources"})
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMarketingSources() {
        return ResponseEntity.ok(ApiResponse.success(analyticsService.getMarketingSources()));
    }

    /** @deprecated {@code GET /staff?role=}. */
    @Deprecated
    @GetMapping("/staff/summary")
    public ResponseEntity<ApiResponse<StaffSummaryResponse>> getStaffSummary(
            @RequestParam(required = false, defaultValue = "monthly") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.success(
            staffAnalyticsService.getStaffSummary(period, from, to)));
    }

    /** @deprecated {@code GET /staff?role=} → {@code links}. */
    @Deprecated
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
}
