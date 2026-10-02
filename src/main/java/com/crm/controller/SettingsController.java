package com.crm.controller;

import com.crm.dto.request.CenterSettingsRequest;
import com.crm.dto.response.ApiResponse;
import com.crm.dto.response.CenterSettingsDto;
import com.crm.service.CenterSettingsService;
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
}
