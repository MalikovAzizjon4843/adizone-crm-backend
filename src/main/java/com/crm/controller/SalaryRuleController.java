package com.crm.controller;

import com.crm.dto.request.SalaryRuleRequest;
import com.crm.dto.response.ApiResponse;
import com.crm.dto.response.SalaryRuleResponse;
import com.crm.service.SalaryRuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/salary-rules")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class SalaryRuleController {

    private final SalaryRuleService salaryRuleService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<SalaryRuleResponse>>> getAll() {
        return ResponseEntity.ok(ApiResponse.success(salaryRuleService.getAll()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<SalaryRuleResponse>> create(@Valid @RequestBody SalaryRuleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Salary rule created", salaryRuleService.create(request)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<SalaryRuleResponse>> update(
            @PathVariable Long id, @Valid @RequestBody SalaryRuleRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Salary rule updated",
            salaryRuleService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        salaryRuleService.delete(id);
        return ResponseEntity.ok(ApiResponse.success("Salary rule deactivated", null));
    }
}
