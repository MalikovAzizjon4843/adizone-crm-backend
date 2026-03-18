package com.crm.controller;
import com.crm.dto.request.ExpenseRequest;
import com.crm.dto.response.*;
import com.crm.service.FinanceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/finance")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT')")
public class FinanceController {
    private final FinanceService financeService;

    @GetMapping("/expenses")
    public ResponseEntity<ApiResponse<List<ExpenseResponse>>> getExpenses(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.success(financeService.getExpenses(from, to)));
    }

    @PostMapping("/expenses")
    public ResponseEntity<ApiResponse<ExpenseResponse>> createExpense(@Valid @RequestBody ExpenseRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Expense recorded", financeService.createExpense(req)));
    }

    @GetMapping("/report")
    public ResponseEntity<ApiResponse<FinanceReportResponse>> getReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.success(financeService.getFinanceReport(from, to)));
    }
}
