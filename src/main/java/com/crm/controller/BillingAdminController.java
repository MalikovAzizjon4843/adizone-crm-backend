package com.crm.controller;

import com.crm.billing.BillingJobService;
import com.crm.dto.response.ApiResponse;
import com.crm.entity.BillingJobRun;
import com.crm.exception.CodedException;
import com.crm.repository.BillingJobRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Billing v2 texnik xizmati — faqat SUPER_ADMIN (docs/design/billing-v2.md §10.2).
 * URL darajasida {@code /api/admin/**} → SA (SecurityConfig), metodda ham SA.
 */
@RestController
@RequestMapping("/api/admin/billing")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class BillingAdminController {

    private final BillingJobService billingJobService;
    private final BillingJobRunRepository jobRunRepository;
    private final com.crm.billing.BillingSnapshotService snapshotService;
    private final com.crm.repository.StudentRepository studentRepository;
    private final Clock billingClock;

    /** Accrual'ni qo'lda ishga tushirish (job bilan bir xil kod). {@code date} default — bugun. */
    @PostMapping("/accrue")
    public ResponseEntity<ApiResponse<BillingJobRun>> accrue(
            @RequestParam(name = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate today = LocalDate.now(billingClock);
        LocalDate target = date != null ? date : today;
        if (target.isAfter(today)) {
            throw CodedException.badRequest("billing.accrue.futureDate");
        }
        return ResponseEntity.ok(ApiResponse.success(billingJobService.runDaily(target, "ADMIN")));
    }

    /**
     * Barcha o'quvchilar snapshot'ini ledgerdan qayta yozadi (holat, debtSince, next). Pulga
     * tegmaydi; har o'quvchi alohida tranzaksiyada (§10.2).
     */
    @PostMapping("/refresh-snapshots")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> refreshSnapshots() {
        int done = 0;
        int failed = 0;
        for (Long id : studentRepository.findAllIds()) {
            try {
                snapshotService.refreshStudentFully(id);
                done++;
            } catch (RuntimeException e) {
                failed++;
            }
        }
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("refreshed", done);
        result.put("failed", failed);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/job-runs")
    public ResponseEntity<ApiResponse<List<BillingJobRun>>> jobRuns(
            @RequestParam(name = "limit", defaultValue = "30") int limit) {
        int size = Math.min(Math.max(limit, 1), 200);
        return ResponseEntity.ok(ApiResponse.success(
            jobRunRepository.findByOrderByStartedAtDescIdDesc(PageRequest.of(0, size))));
    }
}
