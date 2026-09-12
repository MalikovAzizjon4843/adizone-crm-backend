package com.crm.controller;

import com.crm.dto.request.LeadAssignRequest;
import com.crm.dto.request.LeadCommentRequest;
import com.crm.dto.request.LeadConvertRequest;
import com.crm.dto.request.LeadRequest;
import com.crm.dto.request.LeadStatusRequest;
import com.crm.dto.response.ApiResponse;
import com.crm.dto.response.LeadCommentResponse;
import com.crm.dto.response.LeadConvertResponse;
import com.crm.dto.response.LeadOperatorResponse;
import com.crm.dto.response.LeadResponse;
import com.crm.dto.response.LeadStatsResponse;
import com.crm.dto.response.LeadStatusHistoryResponse;
import com.crm.dto.response.PageResponse;
import com.crm.dto.response.TaskResponse;
import com.crm.service.LeadService;
import com.crm.service.TaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Lidlar API.
 *
 * <p>Ruxsat sinf darajasida beriladi: ADMIN/SUPER_ADMIN hamma lidni ko'radi,
 * SALES_MANAGER faqat o'ziga biriktirilganini — doira {@code LeadService}
 * ichida {@code LeadAccessService} orqali majburlanadi, endpointlarda
 * qo'shimcha shart yozilmaydi.
 *
 * <p>Agregat endpointlar ({@code /stats}, {@code /operators}, {@code /export})
 * metod darajasida toraytirilgan: ular butun bazani ko'rsatadi, shuning uchun
 * operatorga berilmaydi.
 *
 * <p>ADMINISTRATOR ataylab yo'q — u ADMIN bilan bir xil bo'lgani uchun
 * olib tashlanadi.
 */
@RestController
@RequestMapping("/api/leads")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','SALES_MANAGER')")
public class LeadController {

    private final LeadService leadService;
    private final TaskService taskService;

    /** Ochiq forma — sinf darajasidagi rol tekshiruvi bu yerda bekor qilinadi. */
    @PostMapping("/public")
    @PreAuthorize("permitAll()")
    public ResponseEntity<ApiResponse<LeadResponse>> createPublicLead(
            @Valid @RequestBody LeadRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        "Ariza qabul qilindi", leadService.createLead(request)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<LeadResponse>>> getAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long assignedUserId,
            @RequestParam(required = false) Boolean unassigned,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate) {
        return ResponseEntity.ok(ApiResponse.success(
                leadService.getAll(page, size, status, search, assignedUserId, unassigned, fromDate, toDate)));
    }

    @GetMapping("/export")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<byte[]> exportLeads(
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long operatorId) {
        byte[] xlsx = leadService.exportLeadsXlsx(fromDate, toDate, status, operatorId);
        String filename = "lidlar_" + LocalDate.now() + ".xlsx";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + filename + "\"");
        return ResponseEntity.ok().headers(headers).body(xlsx);
    }

    @GetMapping("/stats")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<LeadStatsResponse>> getStats() {
        return ResponseEntity.ok(ApiResponse.success(leadService.getStats()));
    }

    @GetMapping("/operators")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<List<LeadOperatorResponse>>> getOperators() {
        return ResponseEntity.ok(ApiResponse.success(leadService.getOperators()));
    }

    @GetMapping("/{id:\\d+}")
    public ResponseEntity<ApiResponse<LeadResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(leadService.getById(id)));
    }

    /** Operatorni almashtirish faqat adminda — operator o'zidan lidni olib tashlay olmaydi. */
    @PatchMapping("/{id:\\d+}/assign")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<LeadResponse>> assignLead(
            @PathVariable Long id,
            @RequestBody LeadAssignRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Operator biriktirildi",
                leadService.assignLead(id, request)));
    }

    @PatchMapping("/{id:\\d+}/status")
    public ResponseEntity<ApiResponse<LeadResponse>> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody LeadStatusRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Status yangilandi",
                leadService.updateStatus(id, request.getStatus())));
    }

    @PostMapping("/{id:\\d+}/comments")
    public ResponseEntity<ApiResponse<LeadCommentResponse>> addComment(
            @PathVariable Long id,
            @Valid @RequestBody LeadCommentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                "Izoh qo'shildi",
                leadService.addComment(id, request)));
    }

    @GetMapping("/{id:\\d+}/comments")
    public ResponseEntity<ApiResponse<PageResponse<LeadCommentResponse>>> getComments(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(leadService.getComments(id, page, size)));
    }

    /** Lid kartasidagi vazifalar: ochiqlar yuqorida, keyin yopilganlar. */
    @GetMapping("/{id:\\d+}/tasks")
    public ResponseEntity<ApiResponse<List<TaskResponse>>> getTasks(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(taskService.getByLead(id)));
    }

    /** Bosqich o'tishlari tarixi — yangidan eskiga. */
    @GetMapping("/{id:\\d+}/history")
    public ResponseEntity<ApiResponse<List<LeadStatusHistoryResponse>>> getHistory(
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(leadService.getHistory(id)));
    }

    @PostMapping("/{id:\\d+}/convert")
    public ResponseEntity<ApiResponse<LeadConvertResponse>> convertToStudent(
            @PathVariable Long id,
            @RequestBody(required = false) LeadConvertRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                "O'quvchiga o'tkazildi",
                leadService.convertToStudent(id, request)));
    }
}
