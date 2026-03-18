package com.crm.controller;
import com.crm.dto.request.PaymentRequest;
import com.crm.dto.response.*;
import com.crm.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {
    private final PaymentService paymentService;

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
