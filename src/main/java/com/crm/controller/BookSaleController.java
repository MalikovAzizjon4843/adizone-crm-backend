package com.crm.controller;

import com.crm.books.BookDtos.EligibilityDto;
import com.crm.books.BookDtos.GiftDto;
import com.crm.books.BookDtos.GiftRequest;
import com.crm.books.BookDtos.SaleDto;
import com.crm.books.BookDtos.SaleRequest;
import com.crm.books.BookGiftService;
import com.crm.books.BookSaleService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Kitob sotuvi va bepul kitob (docs/design/cash-and-books.md §2.2) — SA, ADMIN, ACCOUNTANT. Sotuv kassasi —
 * sotuvchining o'z kassasi (a'zolik; boshqasi — 404).
 */
@RestController
@RequestMapping(value = "/api/books")
@RequiredArgsConstructor
@PreAuthorize(value = "hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT')")
public class BookSaleController {

    private final BookSaleService sales;
    private final BookGiftService gifts;

    /** {@code Idempotency-Key} — takror bosish ikkinchi chek yaratmaydi (200 + {@code idempotentReplay}). */
    @PostMapping(value = "/sales")
    public ResponseEntity<ApiResponse<SaleDto>> sell(
            @RequestBody SaleRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        SaleDto dto = sales.create(request, idempotencyKey);
        return ResponseEntity.status(Boolean.TRUE.equals(dto.idempotentReplay()) ? HttpStatus.OK : HttpStatus.CREATED)
            .body(ApiResponse.success(dto));
    }

    @GetMapping(value = "/sales")
    public ResponseEntity<ApiResponse<Page<SaleDto>>> list(
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "studentId", required = false) Long studentId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(sales.list(from, to, studentId, page, size)));
    }

    @GetMapping(value = "/sales/{id}")
    public ResponseEntity<ApiResponse<SaleDto>> get(@PathVariable(name = "id") Long id) {
        return ResponseEntity.ok(ApiResponse.success(sales.get(id)));
    }

    /** Bepul kitob sharti — sabab bilan ("Kitob berish" tugmasi faol / o'chiq). */
    @GetMapping(value = "/gifts/eligibility")
    public ResponseEntity<ApiResponse<EligibilityDto>> eligibility(
            @RequestParam(name = "studentId") Long studentId,
            @RequestParam(name = "studentGroupId", required = false) Long studentGroupId) {
        return ResponseEntity.ok(ApiResponse.success(gifts.eligibility(studentId, studentGroupId)));
    }

    /** Shart qulf ostida qayta tekshiriladi; bajarilmasa — 409 {@code book.gift.notEligible} ({@code data.reason}). */
    @PostMapping(value = "/gifts")
    public ResponseEntity<ApiResponse<GiftDto>> give(@RequestBody GiftRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(gifts.give(request)));
    }

    @GetMapping(value = "/gifts")
    public ResponseEntity<ApiResponse<Page<GiftDto>>> giftsList(
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(gifts.list(from, to, page, size)));
    }
}
