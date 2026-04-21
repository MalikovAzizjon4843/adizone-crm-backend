package com.crm.controller;

import com.crm.dto.request.PaymentRequest;
import com.crm.dto.response.*;
import com.crm.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
public class PaymentController {

    private final PaymentService paymentService;

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
        return ResponseEntity.ok(ApiResponse.success(
            paymentService.getAllPayments(
                page, size, studentId, groupId,
                status, from, to)));
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

    @GetMapping("/debtors")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT')")
    public ResponseEntity<ApiResponse<List<DebtorResponse>>> getDebtors() {
        return ResponseEntity.ok(ApiResponse.success(paymentService.getDebtors()));
    }
}
