package com.crm.controller;

import com.crm.dto.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

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
}
