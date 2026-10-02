package com.crm.controller;

import com.crm.dto.request.PayrollApproveRequest;
import com.crm.dto.request.PayrollCancelRequest;
import com.crm.dto.request.PayrollGenerateRequest;
import com.crm.dto.request.PayrollPayDto;
import com.crm.dto.response.ApiResponse;
import com.crm.dto.response.PageResponse;
import com.crm.dto.response.PayrollGenerateResult;
import com.crm.dto.response.PayrollResponse;
import com.crm.dto.response.SalaryCalculationDto;
import com.crm.exception.CodedException;
import com.crm.service.PayrollService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Oylik — docs/design/payroll-v2-api.md. Holat faqat amallar orqali o'zgaradi
 * ({@code POST /api/payroll} va {@code PUT /api/payroll/{id}} olib tashlangan).
 */
@RestController
@RequestMapping("/api/payroll")
@RequiredArgsConstructor
public class PayrollController {

    private final PayrollService payrollService;

    @GetMapping("/calculate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT')")
    public ResponseEntity<ApiResponse<List<SalaryCalculationDto>>> calculateAll(
            @RequestParam int month,
            @RequestParam int year) {
        return ResponseEntity.ok(ApiResponse.success(payrollService.previewCalculate(month, year)));
    }

    @GetMapping("/calculate/{userId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT')")
    public ResponseEntity<ApiResponse<SalaryCalculationDto>> calculateUser(
            @PathVariable Long userId,
            @RequestParam int month,
            @RequestParam int year) {
        return ResponseEntity.ok(ApiResponse.success(
            payrollService.previewCalculateUser(userId, month, year)));
    }

    /** Query ({@code ?month&year&recalculate}) yoki body — ikkalasi ham; query ustun. */
    @PostMapping("/generate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT')")
    public ResponseEntity<ApiResponse<PayrollGenerateResult>> generate(
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Boolean recalculate,
            @RequestBody(required = false) PayrollGenerateRequest body) {
        Integer m = month != null ? month : (body != null ? body.getMonth() : null);
        Integer y = year != null ? year : (body != null ? body.getYear() : null);
        Boolean r = recalculate != null ? recalculate : (body != null ? body.getRecalculate() : null);
        if (m == null || y == null) {
            throw CodedException.badRequest("payroll.period.required");
        }
        return ResponseEntity.ok(ApiResponse.success("Oylik yaratildi",
            payrollService.generatePayroll(m, y, Boolean.TRUE.equals(r))));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT')")
    public ResponseEntity<ApiResponse<PageResponse<PayrollResponse>>> getAllPayroll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Long userId) {
        return ResponseEntity.ok(ApiResponse.success(
            payrollService.getAllPayroll(page, size, status, month, year, userId)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT')")
    public ResponseEntity<ApiResponse<PayrollResponse>> getPayrollById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(payrollService.getPayrollById(id)));
    }

    @GetMapping("/teacher/{teacherId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT')")
    public ResponseEntity<ApiResponse<List<PayrollResponse>>> getByTeacher(@PathVariable Long teacherId) {
        return ResponseEntity.ok(ApiResponse.success(payrollService.getPayrollByTeacher(teacherId)));
    }

    @PostMapping("/{id}/recalculate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT')")
    public ResponseEntity<ApiResponse<PayrollResponse>> recalculate(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Oylik qayta hisoblandi", payrollService.recalculate(id)));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<PayrollResponse>> approve(
            @PathVariable Long id,
            @RequestBody(required = false) PayrollApproveRequest body) {
        return ResponseEntity.ok(ApiResponse.success("Oylik tasdiqlandi",
            payrollService.approve(id, body != null ? body.getExpectedNetSalary() : null)));
    }

    /** {@code Idempotency-Key} — frontend to'lov dialogi ochilganda UUID; takror bosish ikkinchi chiqim yozmaydi. */
    @PostMapping("/{id}/pay")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT')")
    public ResponseEntity<ApiResponse<PayrollResponse>> markPayrollPaid(
            @PathVariable Long id,
            @RequestBody(required = false) PayrollPayDto body,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.ok(ApiResponse.success("Oylik to'landi",
            payrollService.markAsPaid(id, body, idempotencyKey)));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<PayrollResponse>> cancel(
            @PathVariable Long id,
            @RequestBody(required = false) PayrollCancelRequest body) {
        return ResponseEntity.ok(ApiResponse.success("Oylik bekor qilindi",
            payrollService.cancel(id, body != null ? body.getReason() : null)));
    }

    /** Faqat DRAFT. */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deletePayroll(@PathVariable Long id) {
        payrollService.deletePayroll(id);
        return ResponseEntity.ok(ApiResponse.success("Oylik qoralamasi o'chirildi", null));
    }
}
