package com.crm.controller;

import com.crm.dto.request.SubstitutionBulkRequest;
import com.crm.dto.request.SubstitutionRequest;
import com.crm.dto.response.ApiResponse;
import com.crm.dto.response.PageResponse;
import com.crm.dto.response.SubstitutionResponse;
import com.crm.entity.enums.SubstitutionStatus;
import com.crm.exception.CodedException;
import com.crm.service.LessonSubstitutionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** "Darsni X o'tdi" (o'rinbosar) — docs/design/leaves-exams-contracts.md §2.2. */
@RestController
@RequestMapping("/api/substitutions")
@RequiredArgsConstructor
public class SubstitutionController {

    private final LessonSubstitutionService substitutionService;

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<SubstitutionResponse>> create(@Valid @RequestBody SubstitutionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Belgilandi", substitutionService.create(request)));
    }

    /** Hammasi yoki hech biri; xatoda {@code data.index} — qaysi element. */
    @PostMapping("/bulk")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<List<SubstitutionResponse>>> bulk(@Valid @RequestBody SubstitutionBulkRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Belgilandi", substitutionService.bulk(request.getItems())));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT')")
    public ResponseEntity<ApiResponse<PageResponse<SubstitutionResponse>>> search(
            @RequestParam(required = false) Long teacherId,
            @RequestParam(required = false) Long substituteTeacherId,
            @RequestParam(required = false) Long groupId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        SubstitutionStatus st = null;
        if (status != null && !status.isBlank()) {
            try {
                st = SubstitutionStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw CodedException.badRequest("substitution.status.invalid", status);
            }
        }
        return ResponseEntity.ok(ApiResponse.success(substitutionService.search(
            new LessonSubstitutionService.Filter(teacherId, substituteTeacherId, groupId, from, to, st), page, size)));
    }

    /** O'qituvchi: o'rinbosar sifatidagi bugungi va kelgusi darslari. */
    @GetMapping("/my")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<ApiResponse<List<SubstitutionResponse>>> my() {
        return ResponseEntity.ok(ApiResponse.success(substitutionService.my()));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<SubstitutionResponse>> cancel(@PathVariable Long id,
                                                                    @RequestBody(required = false) Map<String, String> body) {
        return ResponseEntity.ok(ApiResponse.success("Bekor qilindi",
            substitutionService.cancel(id, body != null ? body.get("reason") : null)));
    }
}
