package com.crm.controller;

import com.crm.books.BookDtos.CostsRequest;
import com.crm.books.BookDtos.ReceiptDto;
import com.crm.books.BookDtos.ReceiptRequest;
import com.crm.books.BookDtos.SupplierDto;
import com.crm.books.BookDtos.SupplierPaymentDto;
import com.crm.books.BookDtos.SupplierPaymentRequest;
import com.crm.books.BookDtos.SupplierRequest;
import com.crm.books.BookReceiptService;
import com.crm.dto.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Yetkazib beruvchilar, kitob kirimi va yetkazib beruvchiga to'lov (docs/design/cash-and-books.md §2.2):
 * kirim (soni) — SA, ADMIN; tannarx, yetkazib beruvchi yozuvi, to'lov — faqat SA (to'lov — Direktor kassasidan).
 */
@RestController
@RequestMapping(value = "/api/books")
@RequiredArgsConstructor
@PreAuthorize(value = "hasAnyRole('SUPER_ADMIN','ADMIN')")
public class BookReceiptController {

    private final BookReceiptService service;

    /** Nomlar ro'yxati — kirim formasi uchun (admin ham). */
    @GetMapping(value = "/suppliers")
    public ResponseEntity<ApiResponse<List<SupplierDto>>> suppliers() {
        return ResponseEntity.ok(ApiResponse.success(service.suppliers()));
    }

    @PostMapping(value = "/suppliers")
    @PreAuthorize(value = "hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<SupplierDto>> createSupplier(@RequestBody SupplierRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(service.createSupplier(request)));
    }

    @PutMapping(value = "/suppliers/{id}")
    @PreAuthorize(value = "hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<SupplierDto>> updateSupplier(@PathVariable(name = "id") Long id,
                                                                   @RequestBody SupplierRequest request) {
        return ResponseEntity.ok(ApiResponse.success(service.updateSupplier(id, request)));
    }

    /** Admin — faqat son (tannarx yuborsa 403 {@code book.cost.directorOnly}); ombor darhol ko'payadi. */
    @PostMapping(value = "/receipts")
    public ResponseEntity<ApiResponse<ReceiptDto>> createReceipt(@RequestBody ReceiptRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(service.create(request)));
    }

    @GetMapping(value = "/receipts")
    public ResponseEntity<ApiResponse<Page<ReceiptDto>>> receipts(
            @RequestParam(name = "supplierId", required = false) Long supplierId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(service.list(supplierId, status, page, size)));
    }

    @GetMapping(value = "/receipts/{id}")
    public ResponseEntity<ApiResponse<ReceiptDto>> receipt(@PathVariable(name = "id") Long id) {
        return ResponseEntity.ok(ApiResponse.success(service.get(id)));
    }

    @PutMapping(value = "/receipts/{id}/costs")
    @PreAuthorize(value = "hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<ReceiptDto>> setCosts(@PathVariable(name = "id") Long id,
                                                            @RequestBody CostsRequest request) {
        return ResponseEntity.ok(ApiResponse.success(service.setCosts(id, request)));
    }

    /** {@code Idempotency-Key} — dialog ochilganda UUID; takror bosish ikkinchi chiqim yozmaydi. */
    @PostMapping(value = "/receipts/{id}/payments")
    @PreAuthorize(value = "hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<SupplierPaymentDto>> pay(
            @PathVariable(name = "id") Long id,
            @RequestBody SupplierPaymentRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        SupplierPaymentDto dto = service.pay(id, request, idempotencyKey);
        return ResponseEntity.status(dto.idempotentReplay() ? HttpStatus.OK : HttpStatus.CREATED)
            .body(ApiResponse.success(dto));
    }
}
