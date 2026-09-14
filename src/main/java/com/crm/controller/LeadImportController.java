package com.crm.controller;

import com.crm.dto.request.LeadImportExecuteRequest;
import com.crm.dto.response.ApiResponse;
import com.crm.dto.response.LeadImportPreviewResponse;
import com.crm.dto.response.LeadImportResult;
import com.crm.exception.BadRequestException;
import com.crm.service.LeadImportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * amoCRM eksportidan lidlarni import qilish.
 *
 * <p>Alohida controller: {@code LeadController} sinf darajasida
 * SALES_MANAGER ga ham ochiq, import esa faqat adminlarga.
 * Yo'l {@code /api/leads/import/...} — {@code LeadController} dagi
 * {@code /{id:\\d+}} raqamli namuna bilan to'qnashmaydi.
 */
@RestController
@RequestMapping("/api/leads/import")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
public class LeadImportController {

    private final LeadImportService leadImportService;

    /** Tahlil: bazaga hech narsa yozilmaydi, javobda {@code importId} keladi. */
    @PostMapping("/preview")
    public ResponseEntity<ApiResponse<LeadImportPreviewResponse>> preview(
            @RequestParam(name = "file") MultipartFile file) {
        return ResponseEntity.ok(ApiResponse.success(
                "Fayl tahlil qilindi", leadImportService.preview(file)));
    }

    @PostMapping("/execute")
    public ResponseEntity<ApiResponse<LeadImportResult>> execute(
            @Valid @RequestBody LeadImportExecuteRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Import tugadi", leadImportService.execute(request)));
    }

    /** Partiyadagi lidlar soni — o'chirishdan oldin ko'rsatish uchun. */
    @GetMapping("/{importBatch}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> countBatch(
            @PathVariable String importBatch) {
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "importBatch", importBatch,
                "count", leadImportService.countBatch(importBatch))));
    }

    /**
     * Butun partiyani o'chiradi — noto'g'ri import qilinganda qaytarish uchun.
     * Qaytarib bo'lmaydi, shuning uchun {@code confirm=true} majburiy.
     */
    @DeleteMapping("/{importBatch}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteBatch(
            @PathVariable String importBatch,
            @RequestParam(name = "confirm", required = false) Boolean confirm) {
        if (!Boolean.TRUE.equals(confirm)) {
            throw new BadRequestException(
                    "Tasdiqlash kerak: ?confirm=true qo'shing");
        }
        long deleted = leadImportService.deleteBatch(importBatch);
        return ResponseEntity.ok(ApiResponse.success(
                "Import partiyasi o'chirildi",
                Map.of("importBatch", importBatch, "deleted", deleted)));
    }
}
