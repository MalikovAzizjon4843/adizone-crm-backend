package com.crm.controller;

import com.crm.billing.HeldEnrollmentService;
import com.crm.dto.response.ApiResponse;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Hold'dagi yozilmalar (migratsiya qo'llanmagan, billing-v2 §9.7) — faqat SUPER_ADMIN. Mavjud
 * {@code /migration/apply-sg} mantiqi CRM uchun: ro'yxat, preview (ixtiyoriy yangi langar bilan) va apply.
 */
@RestController
@RequestMapping("/api/admin/billing/held")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class HeldEnrollmentController {

    private final HeldEnrollmentService heldService;

    @Data
    public static class PreviewRequest {
        /** Langarni shu sanaga o'zgartirib ko'rish (yozilmaydi). */
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate paymentStartDate;
    }

    @Data
    public static class ApplyRequest {
        /** Majburiy (≤ 1000). */
        private String reason;
        /** Berilsa — qo'llashdan oldin langar shu sanaga o'rnatiladi. */
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate paymentStartDate;
        /** Preview'dagi {@code planHash} — reja o'zgargan bo'lsa 409 {@code billing.held.planChanged}. */
        private String expectedPlanHash;
    }

    /** Ro'yxat: o'quvchi, guruh, langar, to'lovlar, dry-run (davrlar, qarz, anomaliyalar). Faqat o'qiydi. */
    @GetMapping
    public ResponseEntity<ApiResponse<HeldEnrollmentService.HeldList>> list() {
        return ResponseEntity.ok(ApiResponse.success(heldService.list()));
    }

    /** Hech narsa yozmaydi. */
    @PostMapping("/{sgId}/preview")
    public ResponseEntity<ApiResponse<HeldEnrollmentService.HeldRow>> preview(
            @PathVariable(name = "sgId") Long sgId,
            @RequestBody(required = false) PreviewRequest r) {
        return ResponseEntity.ok(ApiResponse.success(
            heldService.preview(sgId, r != null ? r.getPaymentStartDate() : null)));
    }

    /** Qo'llash: header {@code Idempotency-Key} va {@code reason} majburiy. */
    @PostMapping("/{sgId}/apply")
    public ResponseEntity<ApiResponse<HeldEnrollmentService.ApplyResult>> apply(
            @PathVariable(name = "sgId") Long sgId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody ApplyRequest r) {
        return ResponseEntity.ok(ApiResponse.success(heldService.apply(
            sgId, idempotencyKey, r.getReason(), r.getPaymentStartDate(), r.getExpectedPlanHash())));
    }
}
