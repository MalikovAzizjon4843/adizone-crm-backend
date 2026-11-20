package com.crm.appeal;

import com.crm.dto.response.ApiResponse;
import com.crm.dto.response.PageResponse;
import com.crm.entity.Appeal;
import com.crm.exception.CodedException;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Murojaatlar (taklif va shikoyatlar) — CRM (docs/design/telegram-platform.md §12.4, api-inventory.md).
 * SA — hammasi; ADMIN — faqat o'zi mas'ul bo'lganlari (boshqasi 404); qolgan rollar — 403.
 */
@RestController
@RequestMapping(value = "/api/appeals")
@RequiredArgsConstructor
@PreAuthorize(value = "hasAnyRole('SUPER_ADMIN','ADMIN')")
public class AppealController {

    private final AppealCrmService service;

    @GetMapping
    public ApiResponse<PageResponse<AppealDtos.ListItem>> list(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "assigneeId", required = false) Long assigneeId,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        Appeal.Status st = status == null || status.isBlank() ? null : AppealCrmService.parseStatus(status);
        Appeal.Type tp = null;
        if (type != null && !type.isBlank()) {
            tp = AppealService.parseType(type).orElseThrow(() -> CodedException.badRequest("appeal.type.invalid", type));
        }
        return ApiResponse.success(service.list(new AppealCrmService.Filter(st, tp, assigneeId, from, to, q), page, size));
    }

    /** Sidebar badge: {@code {"new": N, "inProgress": M}}. */
    @GetMapping(value = "/counts")
    public ApiResponse<AppealDtos.Counts> counts() {
        return ApiResponse.success(service.counts());
    }

    @GetMapping(value = "/stats")
    public ApiResponse<AppealDtos.Stats> stats(
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.success(service.stats(from, to));
    }

    @GetMapping(value = "/{id}")
    public ApiResponse<AppealDtos.Detail> get(@PathVariable(name = "id") Long id) {
        return ApiResponse.success(service.get(id));
    }

    @PatchMapping(value = "/{id}")
    public ApiResponse<AppealDtos.Detail> patch(@PathVariable(name = "id") Long id,
                                                @RequestBody AppealDtos.PatchRequest request) {
        return ApiResponse.success(service.patch(id, request));
    }

    @PostMapping(value = "/{id}/messages")
    public ApiResponse<AppealDtos.Message> addMessage(@PathVariable(name = "id") Long id,
                                                     @RequestBody AppealDtos.StaffMessageRequest request) {
        return ApiResponse.success(service.addStaffMessage(id, request));
    }
}
