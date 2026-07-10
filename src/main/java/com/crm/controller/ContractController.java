package com.crm.controller;

import com.crm.dto.request.ContractCreateDto;
import com.crm.dto.request.ContractTemplateCreateDto;
import com.crm.dto.response.ApiResponse;
import com.crm.dto.response.ContractDto;
import com.crm.dto.response.ContractTemplateDto;
import com.crm.dto.response.PageResponse;
import com.crm.service.ContractService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

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

    @PatchMapping("/contracts/{id}/accept-offer")
    public ResponseEntity<ApiResponse<ContractDto>> acceptOffer(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Taklif qabul qilindi",
            contractService.acceptOffer(id)));
    }

    @PatchMapping("/contracts/{id}/sign")
    public ResponseEntity<ApiResponse<ContractDto>> markSigned(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Shartnoma imzolandi",
            contractService.markSigned(id)));
    }

    @DeleteMapping("/contracts/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteContract(@PathVariable Long id) {
        contractService.deleteContract(id);
        return ResponseEntity.ok(ApiResponse.success("Shartnoma o'chirildi", null));
    }
}
