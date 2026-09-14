package com.crm.controller;

import com.crm.dto.request.LeadStageRequest;
import com.crm.dto.response.ApiResponse;
import com.crm.dto.response.LeadStageResponse;
import com.crm.service.LeadStageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Voronka bosqichlari.
 *
 * <p>O'qish barcha autentifikatsiyadan o'tgan rollarga ochiq — kanban,
 * lid kartasi va filtrlar bosqich nomlarini shu yerdan oladi. Yozish
 * faqat SUPER_ADMIN va ADMIN da.
 */
@RestController
@RequestMapping("/api/lead-stages")
@RequiredArgsConstructor
public class LeadStageController {

    private final LeadStageService leadStageService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<LeadStageResponse>>> getAll() {
        return ResponseEntity.ok(ApiResponse.success(leadStageService.getAll()));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<LeadStageResponse>> create(
            @Valid @RequestBody LeadStageRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                "Bosqich yaratildi", leadStageService.create(request)));
    }

    /** {@code code} va {@code kind} o'zgarmaydi — so'rov tanasida ular yo'q. */
    @PutMapping("/{id:\\d+}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<LeadStageResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody LeadStageRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Bosqich yangilandi", leadStageService.update(id, request)));
    }

    @DeleteMapping("/{id:\\d+}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        leadStageService.delete(id);
        return ResponseEntity.ok(ApiResponse.success("Bosqich o'chirildi", null));
    }

    /** Tana: id lar massivi kerakli tartibda, masalan {@code [3, 1, 2]}. */
    @PatchMapping("/reorder")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<List<LeadStageResponse>>> reorder(
            @RequestBody List<Long> orderedIds) {
        return ResponseEntity.ok(ApiResponse.success(
                "Tartib yangilandi", leadStageService.reorder(orderedIds)));
    }
}
