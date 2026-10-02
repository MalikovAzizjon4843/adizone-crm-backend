package com.crm.controller;

import com.crm.dto.request.ContractCancelRequest;
import com.crm.dto.request.ContractCreateDto;
import com.crm.dto.request.ContractTemplateCreateDto;
import com.crm.dto.response.ApiResponse;
import com.crm.dto.response.ContractDto;
import com.crm.dto.response.ContractTemplateDto;
import com.crm.dto.response.PageResponse;
import com.crm.service.ContractPdfService;
import com.crm.service.ContractPlaceholders;
import com.crm.service.ContractService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
public class ContractController {

    private final ContractService contractService;

    @GetMapping("/contract-templates")
    public ResponseEntity<ApiResponse<List<ContractTemplateDto>>> getAllTemplates() {
        return ResponseEntity.ok(ApiResponse.success(contractService.getAllTemplates()));
    }

    /** Shablonda ishlatiladigan belgilar katalogi (C-08). */
    @GetMapping("/contract-templates/placeholders")
    public ResponseEntity<ApiResponse<List<ContractPlaceholders.Placeholder>>> getPlaceholders() {
        return ResponseEntity.ok(ApiResponse.success(contractService.placeholders()));
    }

    @GetMapping("/contract-templates/{id}")
    public ResponseEntity<ApiResponse<ContractTemplateDto>> getTemplate(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(contractService.getTemplate(id)));
    }

    @PostMapping("/contract-templates")
    public ResponseEntity<ApiResponse<ContractTemplateDto>> createTemplate(
            @Valid @RequestBody ContractTemplateCreateDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Shartnoma shabloni yaratildi", contractService.createTemplate(dto)));
    }

    @PutMapping("/contract-templates/{id}")
    public ResponseEntity<ApiResponse<ContractTemplateDto>> updateTemplate(
            @PathVariable Long id,
            @Valid @RequestBody ContractTemplateCreateDto dto) {
        return ResponseEntity.ok(ApiResponse.success("Shartnoma shabloni yangilandi",
            contractService.updateTemplate(id, dto)));
    }

    @DeleteMapping("/contract-templates/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteTemplate(@PathVariable Long id) {
        contractService.deleteTemplate(id);
        return ResponseEntity.ok(ApiResponse.success("Shartnoma shabloni o'chirildi", null));
    }

    @GetMapping("/contracts")
    public ResponseEntity<ApiResponse<PageResponse<ContractDto>>> getAllContracts(
            @RequestParam(required = false) Long studentId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size,
            Sort.by(Sort.Direction.DESC, "contractDate", "createdAt"));
        return ResponseEntity.ok(ApiResponse.success(
            contractService.getAll(studentId, status, pageable)));
    }

    @GetMapping("/contracts/student/{studentId}")
    public ResponseEntity<ApiResponse<List<ContractDto>>> getByStudent(@PathVariable Long studentId) {
        return ResponseEntity.ok(ApiResponse.success(contractService.getByStudent(studentId)));
    }

    @GetMapping("/contracts/{id}")
    public ResponseEntity<ApiResponse<ContractDto>> getContract(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(contractService.getById(id)));
    }

    @PostMapping("/contracts/generate")
    public ResponseEntity<ApiResponse<ContractDto>> generateContract(
            @Valid @RequestBody ContractCreateDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Shartnoma yaratildi", contractService.generateForStudent(dto)));
    }

    /**
     * Server PDF (§6.3). {@code inline} — brauzerda ochiladi va chop etiladi; {@code download=true} —
     * yuklab olish. SIGNED/ACCEPTED — muzlatilgan nusxa.
     */
    @GetMapping("/contracts/{id}/pdf")
    public ResponseEntity<byte[]> getPdf(@PathVariable Long id,
                                         @RequestParam(defaultValue = "false") boolean download) {
        ContractPdfService.Pdf pdf = contractService.pdf(id);
        ContentDisposition disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
            .filename(pdf.fileName(), StandardCharsets.UTF_8)
            .build();
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .header("X-Content-Type-Options", "nosniff")
            .cacheControl(CacheControl.noStore())
            .body(pdf.bytes());
    }

    /** Chop etish ko'rinishi — PDF bilan bir xil XHTML (skriptsiz). */
    @GetMapping(value = "/contracts/{id}/print", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> getPrintView(@PathVariable Long id) {
        return ResponseEntity.ok()
            .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
            .header("X-Content-Type-Options", "nosniff")
            .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
            .cacheControl(CacheControl.noStore())
            .body(contractService.printHtml(id));
    }

    @PostMapping("/contracts/{id}/accept-offer")
    public ResponseEntity<ApiResponse<ContractDto>> acceptOfferPost(@PathVariable Long id) {
        return acceptOffer(id);
    }

    @PatchMapping("/contracts/{id}/accept-offer")
    public ResponseEntity<ApiResponse<ContractDto>> acceptOffer(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Taklif qabul qilindi",
            contractService.acceptOffer(id)));
    }

    /** DRAFT → SIGNED; PDF muzlatiladi. */
    @PostMapping("/contracts/{id}/sign")
    public ResponseEntity<ApiResponse<ContractDto>> sign(@PathVariable Long id) {
        return markSigned(id);
    }

    /** @deprecated {@code POST /contracts/{id}/sign} */
    @Deprecated
    @PatchMapping("/contracts/{id}/sign")
    public ResponseEntity<ApiResponse<ContractDto>> markSigned(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Shartnoma imzolandi",
            contractService.markSigned(id)));
    }

    @PostMapping("/contracts/{id}/cancel")
    public ResponseEntity<ApiResponse<ContractDto>> cancel(@PathVariable Long id,
                                                           @RequestBody(required = false) ContractCancelRequest body) {
        return ResponseEntity.ok(ApiResponse.success("Shartnoma bekor qilindi",
            contractService.cancel(id, body != null ? body.getReason() : null)));
    }

    /** Faqat imzolanmagan shartnoma; SIGNED/ACCEPTED — 409 {@code contract.signed}. */
    @DeleteMapping("/contracts/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteContract(@PathVariable Long id) {
        contractService.deleteContract(id);
        return ResponseEntity.ok(ApiResponse.success("Shartnoma o'chirildi", null));
    }
}
