package com.crm.controller;

import com.crm.dashboard.DashboardBackfillService;
import com.crm.dto.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Direktor dashboardi texnik xizmati — faqat SUPER_ADMIN (director-dashboard §2.2, §3). */
@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class DashboardAdminController {

    private final DashboardBackfillService backfillService;

    /**
     * Tarixni to'ldirish. Default — DRY-RUN (hech narsa yozmaydi). Qo'llash:
     * {@code dryRun=false&confirm=BACKFILL-APPLY}. Faqat bo'sh maydonlar to'ldiriladi.
     */
    @PostMapping("/backfill")
    public ResponseEntity<ApiResponse<DashboardBackfillService.Report>> backfill(
            @RequestParam(name = "dryRun", defaultValue = "true") boolean dryRun,
            @RequestParam(name = "confirm", required = false) String confirm) {
        return ResponseEntity.ok(ApiResponse.success(backfillService.run(dryRun, confirm)));
    }
}
