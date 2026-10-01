package com.crm.controller;

import com.crm.billing.BillingMigrationService;
import com.crm.billing.MigrationPlanner;
import com.crm.util.MigrationXlsx;
import com.crm.dto.response.ApiResponse;
import com.crm.entity.BillingMigrationRun;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Billing v2 migratsiyasi (§9.4–§9.7) — faqat SUPER_ADMIN. Hech narsa avtomatik ishga
 * tushmaydi; har yozuvchi amal aniq tasdiq parametrini ({@code confirm}) talab qiladi.
 */
@RestController
@RequestMapping("/api/admin/billing")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class BillingMigrationController {

    private static final MediaType XLSX =
        MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final BillingMigrationService migrationService;

    /** DRY-RUN: hech narsa yozmaydi. Natija — SG qatorlari, anomaliyalar, jami va {@code reportHash}. */
    @PostMapping("/migration/dry-run")
    public ResponseEntity<ApiResponse<MigrationPlanner.Report>> dryRun(
            @RequestParam(name = "cutover") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate cutover,
            @RequestParam(name = "a14UsePayable", defaultValue = "false") boolean a14UsePayable) {
        return ResponseEntity.ok(ApiResponse.success(migrationService.dryRun(cutover, a14UsePayable)));
    }

    /** Xuddi shu dry-run, xlsx (egasiga beriladigan hisobot). Hech narsa yozmaydi. */
    @GetMapping("/migration/dry-run.xlsx")
    public ResponseEntity<byte[]> dryRunXlsx(
            @RequestParam(name = "cutover") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate cutover,
            @RequestParam(name = "a14UsePayable", defaultValue = "false") boolean a14UsePayable) {
        MigrationPlanner.Report report = migrationService.dryRun(cutover, a14UsePayable);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=billing-v2-dry-run-" + cutover + ".xlsx")
            .header("X-Report-Hash", report.reportHash())
            .contentType(XLSX)
            .body(MigrationXlsx.dryRun(report));
    }

    @Data
    public static class ApproveRequest {
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate cutover;
        private boolean a14UsePayable;
        private String reportHash;
        /** Hisobotni tasdiqlagan egasi (§13 #20). */
        private String approvedByOwner;
        private String note;
    }

    /** Egasi tasdiqlagan hisobotni qayd etish — hash qayta hisoblanib tekshiriladi. */
    @PostMapping("/migration/approve")
    public ResponseEntity<ApiResponse<BillingMigrationRun>> approve(@RequestBody ApproveRequest r) {
        return ResponseEntity.ok(ApiResponse.success(migrationService.approve(
            r.getCutover(), r.isA14UsePayable(), r.getReportHash(), r.getApprovedByOwner(), r.getNote())));
    }

    @GetMapping("/migration/runs")
    public ResponseEntity<ApiResponse<List<BillingMigrationRun>>> runs() {
        return ResponseEntity.ok(ApiResponse.success(migrationService.runs()));
    }

    @GetMapping("/migration/runs/{id}")
    public ResponseEntity<ApiResponse<BillingMigrationRun>> run(@PathVariable(name = "id") Long id) {
        return ResponseEntity.ok(ApiResponse.success(migrationService.run(id)));
    }

    /**
     * Haqiqiy migratsiya. Talablar: APPROVED run, {@code confirm=APPLY-<runId>},
     * {@code app.billing.enabled=false}, hisobot hash'i o'zgarmagan.
     */
    @PostMapping("/migration/apply")
    public ResponseEntity<ApiResponse<BillingMigrationService.ApplyResult>> apply(
            @RequestParam(name = "runId") Long runId,
            @RequestParam(name = "confirm", required = false) String confirm,
            @RequestParam(name = "exclude", required = false) List<Long> exclude,
            @RequestParam(name = "clearOverrides", defaultValue = "false") boolean clearOverrides) {
        return ResponseEntity.ok(ApiResponse.success(
            migrationService.apply(runId, confirm, exclude, clearOverrides)));
    }

    /** Qisman rollback (§9.7): {@code confirm=REVERT-<runId>-<sgId>}. */
    @PostMapping("/migration/revert-sg")
    public ResponseEntity<ApiResponse<BillingMigrationService.RevertResult>> revertSg(
            @RequestParam(name = "runId") Long runId,
            @RequestParam(name = "sgId") Long sgId,
            @RequestParam(name = "confirm", required = false) String confirm) {
        return ResponseEntity.ok(ApiResponse.success(migrationService.revertSg(runId, sgId, confirm)));
    }

    /** Hold'dagi SG ni qayta qo'llash: {@code confirm=APPLY-<runId>-<sgId>}. */
    @PostMapping("/migration/apply-sg")
    public ResponseEntity<ApiResponse<MigrationPlanner.SgPlan>> applySg(
            @RequestParam(name = "runId") Long runId,
            @RequestParam(name = "sgId") Long sgId,
            @RequestParam(name = "confirm", required = false) String confirm) {
        return ResponseEntity.ok(ApiResponse.success(migrationService.applySg(runId, sgId, confirm)));
    }

    /** I1, I4, I5 tekshiruvi (§9.6 qadam 4). Faqat o'qiydi. */
    @GetMapping("/verify")
    public ResponseEntity<ApiResponse<BillingMigrationService.Verification>> verify() {
        return ResponseEntity.ok(ApiResponse.success(migrationService.verify()));
    }

    /** Rollback uchun (§9.7): {@code from} dan keyin kiritilgan to'lovlar, xlsx. */
    @GetMapping("/payments-since")
    public ResponseEntity<byte[]> paymentsSince(
            @RequestParam(name = "from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from) {
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=payments-since.xlsx")
            .contentType(XLSX)
            .body(migrationService.paymentsSinceXlsx(from));
    }
}
