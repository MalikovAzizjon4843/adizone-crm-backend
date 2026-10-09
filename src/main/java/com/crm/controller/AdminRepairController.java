package com.crm.controller;

import com.crm.dto.response.ApiResponse;
import com.crm.dto.response.LeadMissingTaskRepairResult;
import com.crm.exception.CodedException;
import org.springframework.http.HttpStatus;
import com.crm.service.GroupService;
import com.crm.service.LeadService;
import com.crm.service.LeadTaskRepairService;
import com.crm.service.TeacherProfileSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/repair")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminRepairController {

    private final TeacherProfileSyncService teacherProfileSyncService;
    private final GroupService groupService;
    private final LeadService leadService;
    private final LeadTaskRepairService leadTaskRepairService;

    @PostMapping("/link-teacher-users")
    public ResponseEntity<ApiResponse<Map<String, Object>>> linkTeacherUsers() {
        return ResponseEntity.ok(ApiResponse.success(
            "O'qituvchi-user bog'lanishi tiklandi",
            teacherProfileSyncService.repairTeacherLinks()));
    }

    @PostMapping("/link-timetable-rooms")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> linkTimetableRooms() {
        return ResponseEntity.ok(ApiResponse.success(
            "Timetable xonalari guruhdan bog'landi",
            groupService.linkTimetableRoomsFromGroups()));
    }

    /**
     * Mavjud vazifasiz lidlar: {@code requires_task} bosqichda, ochiq vazifasi yo'q — "Bog'lanish" vazifasi
     * (mas'ulga, u yo'q bo'lsa standart mas'ulga biriktirib). {@code dryRun} standart TRUE — avval ko'rib chiqing.
     */
    @PostMapping("/leads-missing-tasks")
    public ResponseEntity<ApiResponse<LeadMissingTaskRepairResult>> repairLeadsMissingTasks(
            @RequestParam(name = "dryRun", defaultValue = "true") boolean dryRun) {
        return ResponseEntity.ok(ApiResponse.success(
            dryRun ? "Ko'rib chiqish (hech narsa yozilmadi)" : "Vazifasiz lidlar tuzatildi",
            leadTaskRepairService.repairMissingTasks(dryRun)));
    }

    @PostMapping("/migrate-lead-statuses")
    public ResponseEntity<ApiResponse<Map<String, Object>>> migrateLeadStatuses() {
        return ResponseEntity.ok(ApiResponse.success(
            "Lead statuslari yangilandi",
            leadService.migrateLeadStatuses()));
    }

    /**
     * Billing v2: to'lov sanalari, davrlar va ledger endi accrual + snapshot dan hosila
     * (docs/design/billing-v2.md §10.2). Eski ta'mirlash amallari ledgerga qo'lda
     * PERIOD_CHARGE yozardi va accrual bilan to'qnashardi — 410, o'rniga /api/admin/billing/*.
     */
    @PostMapping({"/recalculate-payment-dates", "/fix-payment-periods", "/rebuild-monthly-ledger"})
    public ResponseEntity<ApiResponse<Void>> removedBillingRepairs() {
        throw new CodedException(HttpStatus.GONE, "billing.endpoint.gone", "/api/admin/billing");
    }

    @GetMapping("/verify-balances")
    public ResponseEntity<ApiResponse<Void>> removedVerifyBalances() {
        throw new CodedException(HttpStatus.GONE, "billing.endpoint.gone", "/api/admin/billing/verify");
    }

    /**
     * Eski o'quvchilarda createdBy/attributedUserId NULL qoladi —
     * bonus faqat yangi yozuvlar uchun hisoblanadi.
     */
    @PostMapping("/note-student-attribution")
    public ResponseEntity<ApiResponse<Map<String, Object>>> noteStudentAttribution() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message",
            "Mavjud o'quvchilarda createdBy NULL bo'lsa o'zgartirilmaydi; bonus faqat yangi yozuvlarga");
        result.put("action", "none");
        return ResponseEntity.ok(ApiResponse.success("Student attribution policy", result));
    }
}
