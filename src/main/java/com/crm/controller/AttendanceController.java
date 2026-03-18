package com.crm.controller;
import com.crm.dto.request.AttendanceRequest;
import com.crm.dto.response.*;
import com.crm.service.AttendanceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/attendance")
@RequiredArgsConstructor
public class AttendanceController {
    private final AttendanceService attendanceService;

    @PostMapping("/mark")
    public ResponseEntity<ApiResponse<List<AttendanceResponse>>> markAttendance(
            @Valid @RequestBody AttendanceRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Attendance marked",
            attendanceService.markAttendance(request)));
    }

    @GetMapping("/group/{groupId}")
    public ResponseEntity<ApiResponse<List<AttendanceResponse>>> getGroupAttendance(
            @PathVariable Long groupId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(ApiResponse.success(
            attendanceService.getGroupAttendance(groupId, date)));
    }

    @GetMapping("/student/{studentId}")
    public ResponseEntity<ApiResponse<List<AttendanceResponse>>> getStudentAttendance(
            @PathVariable Long studentId) {
        return ResponseEntity.ok(ApiResponse.success(
            attendanceService.getStudentAttendance(studentId)));
    }
}
