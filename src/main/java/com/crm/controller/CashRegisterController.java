package com.crm.controller;

import com.crm.dto.request.CashRegisterCreateDto;
import com.crm.dto.request.CashRegisterMembersRequest;
import com.crm.dto.response.CashCategoryTotalDto;
import com.crm.dto.request.CashRegisterStatusUpdateDto;
import com.crm.dto.request.ExpenseCreateDto;
import com.crm.dto.request.IncomeCreateDto;
import com.crm.dto.request.TransferDto;
import com.crm.dto.response.ApiResponse;
import com.crm.dto.response.CashBalanceDto;
import com.crm.dto.response.CashChannelReportDto;
import com.crm.dto.response.CashRegisterDto;
import com.crm.dto.response.CashTransactionDto;
import com.crm.service.CashRegisterService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

    /** To'lov / imtihon / xarajat dialoglari: yozish mumkin bo'lgan faol kassalar (admin — o'z kassasi). */
    @GetMapping(value = "/my")
    public ResponseEntity<ApiResponse<List<CashRegisterDto>>> getMine() {
        return ResponseEntity.ok(ApiResponse.success(cashRegisterService.getMine()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<CashRegisterDto>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(cashRegisterService.getById(id)));
    }

    /** A'zolar (ADMIN / ACCOUNTANT) — faqat SA; "bir admin — bitta kassa" (boshqa kassadan ko'chiriladi). */
    @PutMapping(value = "/{id}/members")
    @PreAuthorize(value = "hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<CashRegisterDto>> setMembers(
            @PathVariable(name = "id") Long id, @RequestBody CashRegisterMembersRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Kassa a'zolari yangilandi",
            cashRegisterService.setMembers(id, request.getUserIds())));
    }

    /** Davr kirim/chiqimi kategoriya bo'yicha (kunlik tushum: o'quvchi to'lovi, imtihon, boshqa ...). */
    @GetMapping(value = "/{id}/by-category")
    public ResponseEntity<ApiResponse<List<CashCategoryTotalDto>>> getByCategory(
            @PathVariable(name = "id") Long id,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to) {
        return ResponseEntity.ok(ApiResponse.success(
            cashRegisterService.getCategoryReport(id, parseOptionalDate(from), parseOptionalDate(to))));
    }

    @PostMapping
    @PreAuthorize(value = "hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<CashRegisterDto>> create(
            @RequestBody CashRegisterCreateDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Kassa yaratildi", cashRegisterService.create(dto)));
    }

    @PutMapping("/{id}")
    @PreAuthorize(value = "hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<CashRegisterDto>> update(
            @PathVariable Long id,
            @RequestBody CashRegisterCreateDto dto) {
        return ResponseEntity.ok(ApiResponse.success("Kassa yangilandi",
            cashRegisterService.update(id, dto)));
    }

    /** Kassani o'chirish — faqat SA (V86: kassa tuzilmasi faqat Direktorda). */
    @DeleteMapping("/{id}")
    @PreAuthorize(value = "hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        String message = cashRegisterService.delete(id);
        return ResponseEntity.ok(ApiResponse.success(message, null));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize(value = "hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<CashRegisterDto>> updateStatus(
            @PathVariable Long id,
            @RequestBody CashRegisterStatusUpdateDto dto) {
        if (dto.getStatus() == null || dto.getStatus().isBlank()) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.error("status maydoni ko'rsatilishi shart"));
        }
        return ResponseEntity.ok(ApiResponse.success("Kassa holati yangilandi",
            cashRegisterService.updateStatus(id, dto.getStatus())));
    }

    @GetMapping("/{id}/balance")
    public ResponseEntity<ApiResponse<CashBalanceDto>> getBalance(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(cashRegisterService.getBalance(id)));
    }

    /** Davr oqimi to'lov usuli guruhlari bo'yicha (CASH, CARD, TERMINAL, ONLINE, OTHER). */
    @GetMapping("/{id}/by-method")
    public ResponseEntity<ApiResponse<CashChannelReportDto>> getByMethod(
            @PathVariable Long id,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ResponseEntity.ok(ApiResponse.success(
            cashRegisterService.getChannelReport(id, parseOptionalDate(from), parseOptionalDate(to))));
    }

    /** {@code channel} — to'lov usuli guruhi; {@code paymentMethod} — aniq usul (ikkalasi birga ham bo'ladi). */
    @GetMapping("/{id}/transactions")
    public ResponseEntity<ApiResponse<Page<CashTransactionDto>>> getTransactions(
            @PathVariable Long id,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Long studentId,
            @RequestParam(required = false) Long teacherId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String paymentMethod,
            @RequestParam(required = false) String channel,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        LocalDate fromDate = parseOptionalDate(from);
        LocalDate toDate = parseOptionalDate(to);
        Pageable pageable = PageRequest.of(page, size,
            Sort.by(Sort.Direction.DESC, "transactionDate", "createdAt"));
        return ResponseEntity.ok(ApiResponse.success(
            cashRegisterService.getTransactions(
                id, fromDate, toDate, studentId, teacherId, type, paymentMethod, channel, category, pageable)));
    }

    @GetMapping("/{id}/transactions/export")
    public ResponseEntity<byte[]> exportTransactions(
            @PathVariable Long id,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Long studentId,
            @RequestParam(required = false) Long teacherId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String paymentMethod,
            @RequestParam(required = false) String channel) {
        LocalDate fromDate = parseOptionalDate(from);
        LocalDate toDate = parseOptionalDate(to);
        byte[] file = cashRegisterService.exportTransactions(
            id, fromDate, toDate, studentId, teacherId, type, paymentMethod, channel);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=cash-transactions-" + id + ".xlsx")
            .contentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(file);
    }

    private static LocalDate parseOptionalDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return LocalDate.parse(value);
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
    @PreAuthorize(value = "hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<List<CashTransactionDto>>> transfer(
            @RequestBody TransferDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("O'tkazma bajarildi", cashRegisterService.transfer(dto)));
    }
}
