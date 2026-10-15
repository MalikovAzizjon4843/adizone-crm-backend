package com.crm.controller;

import com.crm.dto.request.CashCategoryRequest;
import com.crm.dto.response.ApiResponse;
import com.crm.dto.response.CashCategoryDto;
import com.crm.service.CashCategoryService;
import lombok.RequiredArgsConstructor;
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

import java.util.List;

/**
 * Kassa kategoriyalari (docs/design/cash-and-books.md §1.2.2). GET: SA — hammasi; ADMIN, ACCOUNTANT —
 * faqat faol DAILY. Yaratish va tahrirlash — faqat SA.
 */
@RestController
@RequestMapping(value = "/api/cash-categories")
@RequiredArgsConstructor
@PreAuthorize(value = "hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT')")
public class CashCategoryController {

    private final CashCategoryService service;

    @GetMapping
    public ResponseEntity<ApiResponse<List<CashCategoryDto>>> list(
            @RequestParam(name = "direction", required = false) String direction) {
        return ResponseEntity.ok(ApiResponse.success(service.list(direction)));
    }

    @PostMapping
    @PreAuthorize(value = "hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<CashCategoryDto>> create(@RequestBody CashCategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(service.create(request)));
    }

    @PutMapping(value = "/{code}")
    @PreAuthorize(value = "hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<CashCategoryDto>> update(
            @PathVariable(name = "code") String code, @RequestBody CashCategoryRequest request) {
        return ResponseEntity.ok(ApiResponse.success(service.update(code, request)));
    }
}
