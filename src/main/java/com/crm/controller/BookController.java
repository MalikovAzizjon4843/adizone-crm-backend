package com.crm.controller;

import com.crm.books.BookCatalogService;
import com.crm.books.BookDtos.AdjustmentRequest;
import com.crm.books.BookDtos.BookDto;
import com.crm.books.BookDtos.BookRequest;
import com.crm.books.BookDtos.MovementDto;
import com.crm.books.BookDtos.SalesReport;
import com.crm.books.BookDtos.SupplierDebtDto;
import com.crm.books.BookReceiptService;
import com.crm.books.BookReportService;
import com.crm.dto.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Kitob katalogi, ombor harakatlari, tuzatish va hisobotlar (docs/design/cash-and-books.md §2.3). Ko'rish — SA,
 * ADMIN, ACCOUNTANT (tannarx — faqat SA javobida); katalog yozuvi, tuzatish, yetkazib beruvchi qarzi — faqat SA.
 */
@RestController
@RequestMapping(value = "/api/books")
@RequiredArgsConstructor
@PreAuthorize(value = "hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT')")
public class BookController {

    private final BookCatalogService catalog;
    private final BookReportService reports;
    private final BookReceiptService receipts;

    @GetMapping
    public ResponseEntity<ApiResponse<List<BookDto>>> list(
            @RequestParam(name = "kind", required = false) String kind,
            @RequestParam(name = "levelId", required = false) Long levelId,
            @RequestParam(name = "active", required = false) Boolean active,
            @RequestParam(name = "lowStock", required = false, defaultValue = "false") boolean lowStock) {
        return ResponseEntity.ok(ApiResponse.success(catalog.list(kind, levelId, active, lowStock)));
    }

    @GetMapping(value = "/{id:\\d+}")
    public ResponseEntity<ApiResponse<BookDto>> get(@PathVariable(name = "id") Long id) {
        return ResponseEntity.ok(ApiResponse.success(catalog.get(id)));
    }

    @PostMapping
    @PreAuthorize(value = "hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<BookDto>> create(@RequestBody BookRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(catalog.create(request)));
    }

    @PutMapping(value = "/{id:\\d+}")
    @PreAuthorize(value = "hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<BookDto>> update(@PathVariable(name = "id") Long id,
                                                       @RequestBody BookRequest request) {
        return ResponseEntity.ok(ApiResponse.success(catalog.update(id, request)));
    }

    @GetMapping(value = "/{id:\\d+}/movements")
    public ResponseEntity<ApiResponse<Page<MovementDto>>> movements(
            @PathVariable(name = "id") Long id,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResponse.success(catalog.movements(id, page, size)));
    }

    /** Tuzatish (SA): boshlang'ich qoldiq — musbat son + tannarx; hisobdan chiqarish — manfiy son. Sabab majburiy. */
    @PostMapping(value = "/adjustments")
    @PreAuthorize(value = "hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<MovementDto>> adjust(@RequestBody AdjustmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(catalog.adjust(request)));
    }

    // ── Hisobotlar ─────────────────────────────────────────────────────

    @GetMapping(value = "/reports/stock")
    public ResponseEntity<ApiResponse<List<BookDto>>> stock(
            @RequestParam(name = "kind", required = false) String kind,
            @RequestParam(name = "levelId", required = false) Long levelId) {
        return ResponseEntity.ok(ApiResponse.success(reports.stock(kind, levelId)));
    }

    @GetMapping(value = "/reports/low-stock")
    public ResponseEntity<ApiResponse<List<BookDto>>> lowStock() {
        return ResponseEntity.ok(ApiResponse.success(reports.lowStock()));
    }

    /** {@code groupBy}: ITEM | KIND | DAY. Admin — o'z kassasidagi sotuvlar; tannarx va marja — faqat SA. */
    @GetMapping(value = "/reports/sales")
    public ResponseEntity<ApiResponse<SalesReport>> sales(
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "groupBy", required = false) String groupBy) {
        return ResponseEntity.ok(ApiResponse.success(reports.sales(from, to, groupBy)));
    }

    @GetMapping(value = "/reports/supplier-debts")
    @PreAuthorize(value = "hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<List<SupplierDebtDto>>> supplierDebts() {
        return ResponseEntity.ok(ApiResponse.success(receipts.debts()));
    }
}
