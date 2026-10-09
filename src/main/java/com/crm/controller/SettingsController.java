package com.crm.controller;

import com.crm.dto.request.CenterSettingsRequest;
import com.crm.dto.request.LeadDefaultAssigneeRequest;
import com.crm.dto.response.ApiResponse;
import com.crm.dto.response.CenterSettingsDto;
import com.crm.dto.response.LeadDefaultAssigneeDto;
import com.crm.service.CenterSettingsService;
import com.crm.service.LeadSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final CenterSettingsService centerSettingsService;
    private final LeadSettingsService leadSettingsService;

    @GetMapping("/academic-year")
    public ResponseEntity<ApiResponse<String>> getAcademicYear() {
        int year = LocalDate.now().getYear();
        int month = LocalDate.now().getMonthValue();
        String academicYear;
        if (month >= 9) {
            academicYear = year + " / " + (year + 1);
        } else {
            academicYear = (year - 1) + " / " + year;
        }
        return ResponseEntity.ok(ApiResponse.success(academicYear));
    }

    /** Markaz rekvizitlari — barcha xodimlar o'qiydi (shartnoma, chek, Mini App). */
    @GetMapping("/center")
    public ResponseEntity<ApiResponse<CenterSettingsDto>> getCenter() {
        return ResponseEntity.ok(ApiResponse.success(centerSettingsService.get()));
    }

    /** Qisman yangilash — faqat SUPER_ADMIN (SecurityConfig ham shu qoidani tutadi). */
    @PutMapping("/center")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<CenterSettingsDto>> updateCenter(@RequestBody CenterSettingsRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Rekvizitlar saqlandi", centerSettingsService.update(request)));
    }

    /** Yangi lidlar uchun standart mas'ul — SA, A (GET /api/settings/** umumiy qoidasidan torroq). */
    @GetMapping("/leads/default-assignee")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<LeadDefaultAssigneeDto>> getLeadDefaultAssignee() {
        return ResponseEntity.ok(ApiResponse.success(leadSettingsService.getDefaultAssignee()));
    }

    /**
     * {@code {"userId": 12}} yoki {@code {"userId": null}} (tozalash). Faol SALES_MANAGER | ADMIN |
     * SUPER_ADMIN bo'lmasa — 400 {@code lead.defaultAssignee.invalid}.
     */
    @PutMapping("/leads/default-assignee")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<LeadDefaultAssigneeDto>> updateLeadDefaultAssignee(
            @RequestBody LeadDefaultAssigneeRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Standart mas'ul saqlandi",
            leadSettingsService.updateDefaultAssignee(request.getUserId())));
    }
}
