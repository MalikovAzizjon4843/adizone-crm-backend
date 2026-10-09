package com.crm.controller;

import com.crm.dto.request.TaskCloseRequest;
import com.crm.dto.request.TaskCompleteRequest;
import com.crm.dto.request.TaskCreateRequest;
import com.crm.dto.request.TaskPostponeRequest;
import com.crm.dto.request.TaskReassignRequest;
import com.crm.dto.request.TaskUpdateRequest;
import com.crm.dto.response.ApiResponse;
import com.crm.dto.response.PageResponse;
import com.crm.dto.response.TaskCompleteResponse;
import com.crm.dto.response.TaskResponse;
import com.crm.dto.response.TaskStatsResponse;
import com.crm.service.TaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Vazifalar API. Rol tekshiruvi bir joyda — sinf darajasidagi
 * {@link PreAuthorize}: ADMIN, SUPER_ADMIN va SALES_HEAD hamma vazifani ko'radi va qayta
 * tayinlaydi, SALES_MANAGER faqat o'ziga tegishlisini (doira {@code TaskService} ichida,
 * {@code LeadAccessService} orqali). Begona vazifani o'chirish — faqat SUPER_ADMIN/ADMIN.
 */
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','SALES_HEAD','SALES_MANAGER')")
public class TaskController {

    private final TaskService taskService;

    @PostMapping
    public ResponseEntity<ApiResponse<TaskResponse>> create(
            @Valid @RequestBody TaskCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                "Vazifa yaratildi", taskService.create(request)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<TaskResponse>>> getAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long assignedTo,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long leadId,
            @RequestParam(required = false) Long studentId,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate) {
        return ResponseEntity.ok(ApiResponse.success(taskService.getAll(
                page, size, assignedTo, status, type, leadId, studentId, fromDate, toDate)));
    }

    /** filter: today | overdue | week | all (bo'sh bo'lsa all). */
    @GetMapping("/my")
    public ResponseEntity<ApiResponse<PageResponse<TaskResponse>>> getMy(
            @RequestParam(required = false) String filter,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(taskService.getMy(filter, page, size)));
    }

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<TaskStatsResponse>> getStats() {
        return ResponseEntity.ok(ApiResponse.success(taskService.getStats()));
    }

    @GetMapping("/{id:\\d+}")
    public ResponseEntity<ApiResponse<TaskResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(taskService.getById(id)));
    }

    @PatchMapping("/{id:\\d+}")
    public ResponseEntity<ApiResponse<TaskResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody TaskUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Vazifa yangilandi", taskService.update(id, request)));
    }

    @PatchMapping("/{id:\\d+}/complete")
    public ResponseEntity<ApiResponse<TaskCompleteResponse>> complete(
            @PathVariable Long id,
            @Valid @RequestBody TaskCompleteRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Vazifa bajarildi", taskService.complete(id, request)));
    }

    @PatchMapping("/{id:\\d+}/reassign")
    public ResponseEntity<ApiResponse<TaskResponse>> reassign(
            @PathVariable Long id,
            @Valid @RequestBody TaskReassignRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Mas'ul o'zgartirildi", taskService.reassign(id, request)));
    }

    @PatchMapping("/{id:\\d+}/postpone")
    public ResponseEntity<ApiResponse<TaskResponse>> postpone(
            @PathVariable Long id,
            @Valid @RequestBody TaskPostponeRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Muddat keyinga surildi", taskService.postpone(id, request)));
    }

    /**
     * Bekor qilish — natijasiz yopish. Lid bosqichi vazifa talab qilsa va bu oxirgi
     * ochiq vazifa bo'lsa {@code nextTask} shart (400 {@code lead.task.required}).
     */
    @PatchMapping("/{id:\\d+}/cancel")
    public ResponseEntity<ApiResponse<TaskResponse>> cancel(
            @PathVariable(name = "id") Long id,
            @Valid @RequestBody(required = false) TaskCloseRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Vazifa bekor qilindi", taskService.cancel(id, request)));
    }

    /** Tana ixtiyoriy: {@code {"nextTask": {...}}} — {@link #cancel} dagi qoida bilan bir xil. */
    @DeleteMapping("/{id:\\d+}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable(name = "id") Long id,
            @Valid @RequestBody(required = false) TaskCloseRequest request) {
        taskService.delete(id, request);
        return ResponseEntity.ok(ApiResponse.success("Vazifa o'chirildi", null));
    }
}
