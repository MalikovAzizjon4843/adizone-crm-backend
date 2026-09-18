package com.crm.controller;

import com.crm.dto.request.MetaFormSettingsRequest;
import com.crm.dto.request.MetaQuestionMappingRequest;
import com.crm.dto.response.ApiResponse;
import com.crm.dto.response.MetaBackfillResultDto;
import com.crm.dto.response.MetaFormDetailDto;
import com.crm.dto.response.MetaFormDto;
import com.crm.dto.response.MetaStatusDto;
import com.crm.dto.response.MetaSyncResultDto;
import com.crm.dto.response.MetaWebhookEventDto;
import com.crm.dto.response.PageResponse;
import com.crm.exception.BadRequestException;
import com.crm.service.MetaAdminService;
import com.crm.service.MetaBackfillService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Meta Lead Ads integratsiyasini sozlash.
 *
 * <p>Butun bo'lim ADMIN va SUPER_ADMIN uchun ({@code SecurityConfig} da
 * ham, shu yerdagi {@code @PreAuthorize} da ham). Yagona ochiq yo'l —
 * {@code /api/meta/webhook}, uni {@link MetaWebhookController} boshqaradi
 * va u imzo bilan himoyalangan.
 */
@RestController
@RequestMapping("/api/meta")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
public class MetaAdminController {

    private final MetaAdminService adminService;
    private final MetaBackfillService backfillService;

    // -- Formalar ---------------------------------------------------------

    /** Graph dan formalarni va savollarni tortadi. Sozlamalar saqlanib qoladi. */
    @PostMapping("/forms/sync")
    public ResponseEntity<ApiResponse<MetaSyncResultDto>> sync() {
        return ResponseEntity.ok(ApiResponse.success(
            "Formalar sinxronlandi", adminService.sync()));
    }

    @GetMapping("/forms")
    public ResponseEntity<ApiResponse<List<MetaFormDto>>> listForms() {
        return ResponseEntity.ok(ApiResponse.success(adminService.listForms()));
    }

    @GetMapping("/forms/{formId:\\d+}")
    public ResponseEntity<ApiResponse<MetaFormDetailDto>> getForm(
            @PathVariable String formId) {
        return ResponseEntity.ok(ApiResponse.success(adminService.getForm(formId)));
    }

    @PutMapping("/forms/{formId:\\d+}")
    public ResponseEntity<ApiResponse<MetaFormDetailDto>> updateForm(
            @PathVariable String formId,
            @Valid @RequestBody MetaFormSettingsRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
            "Sozlamalar saqlandi", adminService.updateForm(formId, request)));
    }

    /** Tana: {@code [{"questionKey": "...", "crmField": "PHONE"}, ...]}. */
    @PutMapping("/forms/{formId:\\d+}/mapping")
    public ResponseEntity<ApiResponse<MetaFormDetailDto>> updateMapping(
            @PathVariable String formId,
            @Valid @RequestBody List<MetaQuestionMappingRequest> mappings) {
        return ResponseEntity.ok(ApiResponse.success(
            "Mapping saqlandi", adminService.updateMapping(formId, mappings)));
    }

    /**
     * Eski lidlarni tortadi.
     *
     * <p>Birinchi marta HAR DOIM {@code dryRun=true} bilan chaqiring: 776
     * lidli formada xato sozlama kanbanga 776 ta keraksiz karta to'kadi va
     * ularni qaytarib olish qo'lda o'chirishdan iborat bo'ladi.
     *
     * @param since {@code yyyy-MM-dd} — shu sanadan oldingilari o'tkaziladi
     */
    @PostMapping("/forms/{formId:\\d+}/backfill")
    public ResponseEntity<ApiResponse<MetaBackfillResultDto>> backfill(
            @PathVariable String formId,
            @RequestParam(name = "since", required = false) String since,
            @RequestParam(name = "dryRun", defaultValue = "true") boolean dryRun) {

        MetaBackfillResultDto result =
            backfillService.backfillForm(formId, parseDate(since), dryRun);
        return ResponseEntity.ok(ApiResponse.success(
            dryRun ? "Sinov o'tkazildi, hech narsa saqlanmadi" : "Backfill tugadi",
            result));
    }

    // -- Eventlar ---------------------------------------------------------

    /** @param status PENDING | PROCESSING | PROCESSED | FAILED | SKIPPED, yoki bo'sh */
    @GetMapping("/events")
    public ResponseEntity<ApiResponse<PageResponse<MetaWebhookEventDto>>> listEvents(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResponse.success(
            adminService.listEvents(status, page, size)));
    }

    @PostMapping("/events/{id:\\d+}/retry")
    public ResponseEntity<ApiResponse<MetaWebhookEventDto>> retryEvent(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(
            "Event navbatga qaytarildi", adminService.retryEvent(id)));
    }

    // -- Holat va obuna ---------------------------------------------------

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<MetaStatusDto>> status() {
        return ResponseEntity.ok(ApiResponse.success(adminService.status()));
    }

    /** {@code POST /{pageId}/subscribed_apps?subscribed_fields=leadgen}. */
    @PostMapping("/subscribe")
    public ResponseEntity<ApiResponse<JsonNode>> subscribe() {
        return ResponseEntity.ok(ApiResponse.success(
            "Sahifa leadgen ga obuna qilindi", adminService.subscribe()));
    }

    @GetMapping("/subscribe")
    public ResponseEntity<ApiResponse<JsonNode>> subscriptions() {
        return ResponseEntity.ok(ApiResponse.success(adminService.subscriptions()));
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (Exception e) {
            throw new BadRequestException("Noto'g'ri since formati (yyyy-MM-dd): " + value);
        }
    }
}
