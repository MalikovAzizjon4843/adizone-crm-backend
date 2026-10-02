package com.crm.controller;

import com.crm.dashboard.LessonCalendarService;
import com.crm.dto.response.ApiResponse;
import com.crm.entity.Holiday;
import com.crm.entity.LessonException;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** Dam olish kunlari va dars istisnolari (director-dashboard §3.5, §4.2 sozlamalar). */
@RestController
@RequiredArgsConstructor
public class LessonCalendarController {

    private final LessonCalendarService calendar;

    @GetMapping("/api/holidays")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<List<Holiday>>> holidays(
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.success(calendar.holidays(from, to)));
    }

    @PostMapping("/api/holidays")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<Holiday>> addHoliday(@RequestBody LessonCalendarService.HolidayRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(calendar.addHoliday(request)));
    }

    @DeleteMapping("/api/holidays/{date}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteHoliday(
            @PathVariable(name = "date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        calendar.deleteHoliday(date);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @GetMapping("/api/groups/{groupId}/lesson-exceptions")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEACHER')")
    public ResponseEntity<ApiResponse<List<LessonException>>> exceptions(@PathVariable(name = "groupId") Long groupId) {
        return ResponseEntity.ok(ApiResponse.success(calendar.exceptions(groupId)));
    }

    /** O'qituvchi — faqat o'z guruhi uchun. */
    @PostMapping("/api/groups/{groupId}/lesson-exceptions")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEACHER')")
    public ResponseEntity<ApiResponse<LessonException>> addException(
            @PathVariable(name = "groupId") Long groupId,
            @RequestBody LessonCalendarService.ExceptionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success(calendar.addException(groupId, request)));
    }

    @DeleteMapping("/api/groups/{groupId}/lesson-exceptions/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEACHER')")
    public ResponseEntity<ApiResponse<Void>> deleteException(@PathVariable(name = "groupId") Long groupId,
                                                             @PathVariable(name = "id") Long id) {
        calendar.deleteException(groupId, id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
