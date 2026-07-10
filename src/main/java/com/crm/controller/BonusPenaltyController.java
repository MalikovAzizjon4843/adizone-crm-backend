package com.crm.controller;

import com.crm.dto.request.BonusPenaltyCreateDto;
import com.crm.dto.response.ApiResponse;
import com.crm.dto.response.BonusPenaltyDto;
import com.crm.dto.response.BonusPenaltyPreviewDto;
import com.crm.dto.response.BonusPenaltySummaryDto;
import com.crm.dto.response.PageResponse;
import com.crm.service.BonusPenaltyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/bonus-penalties")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT')")
public class BonusPenaltyController {

    private final BonusPenaltyService bonusPenaltyService;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<BonusPenaltyDto>>> getAll(
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) Long studentId,
            @RequestParam(required = false) Long teacherId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size,
            Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(ApiResponse.success(
            bonusPenaltyService.getAll(kind, targetType, studentId, teacherId, status, pageable)));
    }

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<BonusPenaltySummaryDto>> getSummary(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ResponseEntity.ok(ApiResponse.success(
            bonusPenaltyService.getSummary(parseOptionalDate(from), parseOptionalDate(to))));
    }

    @GetMapping("/preview/teacher/{teacherId}")
    public ResponseEntity<ApiResponse<BonusPenaltyPreviewDto>> previewForTeacher(
            @PathVariable Long teacherId,
            @RequestParam(required = false) String upToDate) {
        LocalDate cutoff = parseOptionalDate(upToDate);
        if (cutoff == null) {
            cutoff = LocalDate.now();
        }
        return ResponseEntity.ok(ApiResponse.success(
            bonusPenaltyService.previewForTeacher(teacherId, cutoff)));
    }

    @GetMapping("/preview/student/{studentId}")
    public ResponseEntity<ApiResponse<BonusPenaltyPreviewDto>> previewForStudent(
            @PathVariable Long studentId,
            @RequestParam(required = false) String upToDate) {
        LocalDate cutoff = parseOptionalDate(upToDate);
        if (cutoff == null) {
            cutoff = LocalDate.now();
        }
        return ResponseEntity.ok(ApiResponse.success(
            bonusPenaltyService.previewForStudent(studentId, cutoff)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<BonusPenaltyDto>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(bonusPenaltyService.getById(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<BonusPenaltyDto>> create(
            @Valid @RequestBody BonusPenaltyCreateDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Yozuv yaratildi", bonusPenaltyService.create(dto)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<BonusPenaltyDto>> update(
            @PathVariable Long id,
            @Valid @RequestBody BonusPenaltyCreateDto dto) {
        return ResponseEntity.ok(ApiResponse.success("Yozuv yangilandi",
            bonusPenaltyService.update(id, dto)));
    }

    @PatchMapping("/{id}/cancel")
    public ResponseEntity<ApiResponse<BonusPenaltyDto>> cancel(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Yozuv bekor qilindi",
            bonusPenaltyService.cancel(id)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        bonusPenaltyService.delete(id);
        return ResponseEntity.ok(ApiResponse.success("Yozuv o'chirildi", null));
    }

    private static LocalDate parseOptionalDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return LocalDate.parse(value);
    }
}
