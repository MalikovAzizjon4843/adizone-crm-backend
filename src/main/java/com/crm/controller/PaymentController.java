package com.crm.controller;

import com.crm.dto.request.PaymentPreviewRequest;
import com.crm.dto.request.PaymentRequest;
import com.crm.dto.response.*;
import com.crm.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Slf4j
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * data — avvalgidek Page (struktura o'zgarmagan).
     * meta — filtrga mos BARCHA qatorlar bo'yicha aggregat, sahifadan emas.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT')")
    public ResponseEntity<ApiResponse<Page<PaymentResponse>>> getAll(
            @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="50") int size,
            @RequestParam(required=false) Long studentId,
            @RequestParam(required=false) Long groupId,
            @RequestParam(required=false) String status,
            @RequestParam(required=false) String from,
            @RequestParam(required=false) String to) {
        Page<PaymentResponse> rows = paymentService.getAllPayments(
            page, size, studentId, groupId, status, from, to);

        // Aggregat qo'shimcha ma'lumot — u yiqilsa ham ro'yxat ochilishi SHART.
        // Frontend meta yo'q bo'lsa "—" ko'rsatadi.
        PaymentSummary summary = null;
        try {
            summary = paymentService.getPaymentsSummary(studentId, groupId, status, from, to);
        } catch (Exception e) {
            log.error("To'lovlar aggregati hisoblanmadi (ro'yxat meta'siz qaytarildi)", e);
        }
        return ResponseEntity.ok(ApiResponse.successWithMeta(rows, summary));
    }

    @GetMapping("/stats")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStats() {
        return ResponseEntity.ok(ApiResponse.success(paymentService.getPaymentStats()));
    }

    @GetMapping("/archived")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT')")
    public ResponseEntity<ApiResponse<List<SuspendedStudentResponse>>> getArchivedSuspended() {
        return ResponseEntity.ok(ApiResponse.success(paymentService.getArchivedSuspendedStudents()));
    }

    @GetMapping("/history")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT')")
    public ResponseEntity<ApiResponse<List<PaymentHistoryResponse>>> getHistory() {
        return ResponseEntity.ok(ApiResponse.success(paymentService.getPaymentHistory()));
    }

    /** Saqlamasdan hisoblab beradi — frontend summani o'zi hisoblamasligi uchun. */
    @PostMapping("/preview")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT')")
    public ResponseEntity<ApiResponse<PaymentPreviewResponse>> previewPayment(
            @Valid @RequestBody PaymentPreviewRequest request) {
        return ResponseEntity.ok(ApiResponse.success(paymentService.previewPayment(request)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT')")
    public ResponseEntity<ApiResponse<PaymentResponse>> createPayment(@Valid @RequestBody PaymentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Payment recorded", paymentService.createPayment(request)));
    }

    @GetMapping("/student/{studentId}")
    public ResponseEntity<ApiResponse<List<PaymentResponse>>> getStudentPayments(@PathVariable Long studentId) {
        return ResponseEntity.ok(ApiResponse.success(paymentService.getStudentPayments(studentId)));
    }

    @GetMapping("/expected")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT')")
    public ResponseEntity<ApiResponse<ExpectedPaymentsResponse>> getExpected(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.success(
            paymentService.getExpectedPayments(from, to)));
    }

    @GetMapping("/debtors")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT')")
    public ResponseEntity<ApiResponse<DebtorsListResponse>> getDebtors() {
        return ResponseEntity.ok(ApiResponse.success(paymentService.getDebtors()));
    }

    @GetMapping("/calculate-debt")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> calculateDebt(
            @RequestParam Long studentId,
            @RequestParam Long groupId) {
        return ResponseEntity.ok(ApiResponse.success(
            paymentService.calculateStudentDebt(studentId, groupId)));
    }

    @GetMapping("/debtors/summary")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDebtorsSummary() {
        return ResponseEntity.ok(ApiResponse.success(
            paymentService.getDebtorsSummary()));
    }
}
