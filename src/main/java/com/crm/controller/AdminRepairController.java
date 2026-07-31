package com.crm.controller;

import com.crm.dto.response.ApiResponse;
import com.crm.service.BalanceTransactionService;
import com.crm.service.GroupService;
import com.crm.service.LeadService;
import com.crm.service.PaymentScheduleService;
import com.crm.service.TeacherService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/repair")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminRepairController {

    private final TeacherService teacherService;
    private final GroupService groupService;
    private final PaymentScheduleService paymentScheduleService;
    private final LeadService leadService;
    private final BalanceTransactionService balanceTransactionService;

    @PostMapping("/link-teacher-users")
    public ResponseEntity<ApiResponse<Map<String, Object>>> linkTeacherUsers() {
        return ResponseEntity.ok(ApiResponse.success(
            "O'qituvchi-user bog'lanishi yangilandi",
            teacherService.linkTeacherUsers()));
    }

    @PostMapping("/link-timetable-rooms")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> linkTimetableRooms() {
        return ResponseEntity.ok(ApiResponse.success(
            "Timetable xonalari guruhdan bog'landi",
            groupService.linkTimetableRoomsFromGroups()));
    }

    @PostMapping("/recalculate-payment-dates")
    public ResponseEntity<ApiResponse<Map<String, Object>>> recalculatePaymentDates() {
        return ResponseEntity.ok(ApiResponse.success(
            "To'lov sanalari qayta hisoblandi",
            paymentScheduleService.recalculateAllActiveStudents()));
    }

    @PostMapping("/fix-payment-periods")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> fixPaymentPeriods() {
        return ResponseEntity.ok(ApiResponse.success(
            "To'lov davrlari tuzatildi",
            paymentScheduleService.fixPaymentPeriods()));
    }

    @PostMapping("/migrate-lead-statuses")
    public ResponseEntity<ApiResponse<Map<String, Object>>> migrateLeadStatuses() {
        return ResponseEntity.ok(ApiResponse.success(
            "Lead statuslari yangilandi",
            leadService.migrateLeadStatuses()));
    }

    @GetMapping("/verify-balances")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyBalances() {
        return ResponseEntity.ok(ApiResponse.success(
            "Balans tekshiruvi",
            balanceTransactionService.verifyBalances()));
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
