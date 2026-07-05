package com.crm.controller;

import com.crm.dto.request.CashRegisterCreateDto;
import com.crm.dto.request.ExpenseCreateDto;
import com.crm.dto.request.IncomeCreateDto;
import com.crm.dto.request.TransferDto;
import com.crm.dto.response.ApiResponse;
import com.crm.dto.response.CashRegisterDto;
import com.crm.dto.response.CashTransactionDto;
import com.crm.service.CashRegisterService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/cash-registers")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT')")
public class CashRegisterController {

    private final CashRegisterService cashRegisterService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<CashRegisterDto>>> getAll(
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(ApiResponse.success(cashRegisterService.getAll(status)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<CashRegisterDto>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(cashRegisterService.getById(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CashRegisterDto>> create(
            @RequestBody CashRegisterCreateDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Kassa yaratildi", cashRegisterService.create(dto)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<CashRegisterDto>> update(
            @PathVariable Long id,
            @RequestBody CashRegisterCreateDto dto) {
        return ResponseEntity.ok(ApiResponse.success("Kassa yangilandi",
            cashRegisterService.update(id, dto)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        cashRegisterService.delete(id);
        return ResponseEntity.ok(ApiResponse.success("Kassa arxivlandi", null));
    }

    @GetMapping("/{id}/transactions")
    public ResponseEntity<ApiResponse<Page<CashTransactionDto>>> getTransactions(
            @PathVariable Long id,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long studentId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String paymentMethod,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size,
            Sort.by(Sort.Direction.DESC, "transactionDate", "createdAt"));
        return ResponseEntity.ok(ApiResponse.success(
            cashRegisterService.getTransactions(id, from, to, studentId, type, paymentMethod, pageable)));
    }

    @PostMapping("/{id}/income")
    public ResponseEntity<ApiResponse<CashTransactionDto>> addIncome(
            @PathVariable Long id,
            @RequestBody IncomeCreateDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Kirim qo'shildi", cashRegisterService.addIncome(id, dto)));
    }

    @PostMapping("/{id}/expense")
    public ResponseEntity<ApiResponse<CashTransactionDto>> addExpense(
            @PathVariable Long id,
            @RequestBody ExpenseCreateDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Chiqim qo'shildi", cashRegisterService.addExpense(id, dto)));
    }

    @PostMapping("/transfer")
    public ResponseEntity<ApiResponse<List<CashTransactionDto>>> transfer(
            @RequestBody TransferDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("O'tkazma bajarildi", cashRegisterService.transfer(dto)));
    }
}
