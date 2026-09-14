package com.crm.controller;

import com.crm.dto.request.LeadAssignRequest;
import com.crm.dto.request.LeadCommentRequest;
import com.crm.dto.request.LeadConvertRequest;
import com.crm.dto.request.LeadCreateRequest;
import com.crm.dto.request.LeadNoteRequest;
import com.crm.dto.request.LeadRequest;
import com.crm.dto.request.LeadStatusRequest;
import com.crm.dto.response.ApiResponse;
import com.crm.dto.response.LeadCommentResponse;
import com.crm.dto.response.LeadConvertResponse;
import com.crm.dto.response.LeadOperatorResponse;
import com.crm.dto.response.LeadResponse;
import com.crm.dto.response.LeadKanbanStatsResponse;
import com.crm.dto.response.LeadStatsResponse;
import com.crm.dto.response.LeadNoteResponse;
import com.crm.dto.response.LeadStatusHistoryResponse;
import com.crm.dto.response.LeadTimelineResponse;
import com.crm.dto.response.PageResponse;
import com.crm.dto.response.TaskResponse;
import com.crm.service.LeadService;
import com.crm.service.LeadTimelineService;
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
 */
@RestController
@RequestMapping("/api/leads")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','SALES_MANAGER')")
public class LeadController {

    private final LeadService leadService;
    private final LeadTimelineService leadTimelineService;
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

    /**
     * Xodim tomonidan lid yaratish — kanbandagi tez qo'shish.
     * Ruxsat sinf darajasidan: SUPER_ADMIN, ADMIN, SALES_MANAGER.
     * Ochiq sayt formasi uchun alohida {@code POST /public} bor.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<LeadResponse>> createLead(
            @Valid @RequestBody LeadCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                "Lid yaratildi", leadService.createLeadByStaff(request)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<LeadResponse>>> getAll(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "assignedUserId", required = false) Long assignedUserId,
            @RequestParam(name = "unassigned", required = false) Boolean unassigned,
            @RequestParam(name = "fromDate", required = false) String fromDate,
            @RequestParam(name = "toDate", required = false) String toDate) {
        return ResponseEntity.ok(ApiResponse.success(
                leadService.getAll(page, size, status, search, assignedUserId, unassigned, fromDate, toDate)));
    }

    @GetMapping("/export")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<byte[]> exportLeads(
            @RequestParam(name = "fromDate", required = false) String fromDate,
            @RequestParam(name = "toDate", required = false) String toDate,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "operatorId", required = false) Long operatorId) {
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

    /**
     * Kanban sarlavhalari. {@code /stats} dan farqli, bu SALES_MANAGER ga
     * ham ochiq: javob uning o'z lidlari bilan cheklanadi.
     */
    @GetMapping("/kanban-stats")
    public ResponseEntity<ApiResponse<LeadKanbanStatsResponse>> getKanbanStats() {
        return ResponseEntity.ok(ApiResponse.success(leadService.getKanbanStats()));
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
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(leadService.getComments(id, page, size)));
    }

    /** Lid kartasidagi vazifalar: ochiqlar yuqorida, keyin yopilganlar. */
    @GetMapping("/{id:\\d+}/tasks")
    public ResponseEntity<ApiResponse<List<TaskResponse>>> getTasks(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(taskService.getByLead(id)));
    }

    /**
     * Lid kartasining yagona xronologik lentasi: vazifalar, bosqich
     * o'tishlari, izohlar, mas'ul almashuvi va lidning yaratilgani.
     * Ochiq vazifalar lentaga aralashmaydi — {@code openTasks} maydonida.
     */
    @GetMapping("/{id:\\d+}/timeline")
    public ResponseEntity<ApiResponse<LeadTimelineResponse>> getTimeline(
            @PathVariable Long id,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResponse.success(
                leadTimelineService.getTimeline(id, page, size)));
    }

    @PostMapping("/{leadId:\\d+}/notes")
    public ResponseEntity<ApiResponse<LeadNoteResponse>> addNote(
            @PathVariable Long leadId,
            @Valid @RequestBody LeadNoteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                "Izoh qo'shildi", leadService.addNote(leadId, request)));
    }

    @GetMapping("/{leadId:\\d+}/notes")
    public ResponseEntity<ApiResponse<List<LeadNoteResponse>>> getNotes(
            @PathVariable Long leadId) {
        return ResponseEntity.ok(ApiResponse.success(leadService.getNotes(leadId)));
    }

    /** Faqat muallif. */
    @PutMapping("/notes/{id:\\d+}")
    public ResponseEntity<ApiResponse<LeadNoteResponse>> updateNote(
            @PathVariable Long id,
            @Valid @RequestBody LeadNoteRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Izoh yangilandi", leadService.updateNote(id, request)));
    }

    /** Muallif yoki SUPER_ADMIN/ADMIN. */
    @DeleteMapping("/notes/{id:\\d+}")
    public ResponseEntity<ApiResponse<Void>> deleteNote(@PathVariable Long id) {
        leadService.deleteNote(id);
        return ResponseEntity.ok(ApiResponse.success("Izoh o'chirildi", null));
    }

    /** Bosqich o'tishlari tarixi — yangidan eskiga. */
    @GetMapping("/{id:\\d+}/history")
    public ResponseEntity<ApiResponse<List<LeadStatusHistoryResponse>>> getHistory(
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(leadService.getHistory(id)));
    }

    /**
     * Tana endi MAJBURIY: {@code studyFormat} siz lid qaysi konvert
     * bosqichiga tushishini aniqlab bo'lmaydi. Avval {@code required = false}
     * edi va {@code @Valid} ham yo'q edi — ikkalasi ham tuzatildi.
     */
    @PostMapping("/{id:\\d+}/convert")
    public ResponseEntity<ApiResponse<LeadConvertResponse>> convertToStudent(
            @PathVariable Long id,
            @Valid @RequestBody LeadConvertRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                "O'quvchiga o'tkazildi",
                leadService.convertToStudent(id, request)));
    }
}
