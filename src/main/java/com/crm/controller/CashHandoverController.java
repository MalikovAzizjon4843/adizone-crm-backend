package com.crm.controller;

import com.crm.dto.request.CashHandoverRequests;
import com.crm.dto.response.ApiResponse;
import com.crm.dto.response.CashHandoverDto;
import com.crm.dto.response.CashHandoverPreviewDto;
import com.crm.service.CashHandoverService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Kunlik kassa topshirish (docs/design/cash-and-books.md §1.2.3, V86). Admin (ADMIN, ACCOUNTANT) — o'z
 * kassasini ko'radi va topshiradi; qabul / rad etish va hisobot — faqat SA.
 */
@RestController
@RequiredArgsConstructor
@PreAuthorize(value = "hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT')")
public class CashHandoverController {

    private final CashHandoverService service;

    @GetMapping(value = "/api/cash-registers/{id}/handover/preview")
    public ResponseEntity<ApiResponse<CashHandoverPreviewDto>> preview(@PathVariable(name = "id") Long id) {
        return ResponseEntity.ok(ApiResponse.success(service.preview(id)));
    }

    /** {@code Idempotency-Key} — dialog ochilganda UUID; takror bosish ikkinchi topshirish yaratmaydi. */
    @PostMapping(value = "/api/cash-registers/{id}/handovers")
    public ResponseEntity<ApiResponse<CashHandoverDto>> submit(
            @PathVariable(name = "id") Long id,
            @RequestBody CashHandoverRequests.Submit request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        CashHandoverDto dto = service.submit(id, request, idempotencyKey);
        return ResponseEntity.status(dto.isIdempotentReplay() ? HttpStatus.OK : HttpStatus.CREATED)
            .body(ApiResponse.success("Kassa topshirildi", dto));
    }

    @GetMapping(value = "/api/cash-handovers")
    public ResponseEntity<ApiResponse<Page<CashHandoverDto>>> list(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cashRegisterId", required = false) Long cashRegisterId,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(service.list(status, cashRegisterId, from, to, page, size)));
    }

    /** SA: qabul qilingan topshirishlar kassa bo'yicha — naqd, kamomad, ortiqcha. */
    @GetMapping(value = "/api/cash-handovers/report")
    @PreAuthorize(value = "hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> report(
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.success(service.report(from, to)));
    }

    @GetMapping(value = "/api/cash-handovers/{id:\\d+}")
    public ResponseEntity<ApiResponse<CashHandoverDto>> get(@PathVariable(name = "id") Long id) {
        return ResponseEntity.ok(ApiResponse.success(service.get(id)));
    }

    @PostMapping(value = "/api/cash-handovers/{id}/accept")
    @PreAuthorize(value = "hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<CashHandoverDto>> accept(
            @PathVariable(name = "id") Long id, @RequestBody CashHandoverRequests.Accept request) {
        return ResponseEntity.ok(ApiResponse.success("Topshirish qabul qilindi", service.accept(id, request)));
    }

    @PostMapping(value = "/api/cash-handovers/{id}/reject")
    @PreAuthorize(value = "hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<CashHandoverDto>> reject(
            @PathVariable(name = "id") Long id, @RequestBody CashHandoverRequests.Reject request) {
        return ResponseEntity.ok(ApiResponse.success("Topshirish rad etildi", service.reject(id, request)));
    }
}
