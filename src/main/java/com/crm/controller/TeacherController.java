package com.crm.controller;

import com.crm.dto.request.TeacherRequest;
import com.crm.dto.response.*;
import com.crm.exception.BadRequestException;
import com.crm.service.FileStorageService;
import com.crm.service.ImportService;
import com.crm.service.StaffStatusService;
import com.crm.service.TeacherKpiService;
import com.crm.service.TeacherProfileSyncService;
import com.crm.service.TeacherService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/teachers")
@RequiredArgsConstructor
public class TeacherController {

    private final TeacherService teacherService;
    private final FileStorageService fileStorageService;
    private final ImportService importService;
    private final TeacherProfileSyncService teacherProfileSyncService;
    private final StaffStatusService staffStatusService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<TeacherResponse>>> getAllTeachers(
            @RequestParam(defaultValue = "true") boolean activeOnly) {
        return ResponseEntity.ok(ApiResponse.success(teacherService.getAllTeachers(activeOnly)));
    }

    @GetMapping("/me/kpi")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<TeacherKpiDto> myKpi(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(required = false, defaultValue = "monthly") String period,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        to = TeacherKpiService.defaultTo(to);
        from = TeacherKpiService.defaultFrom(period, from, to);
        return ResponseEntity.ok(
            teacherService.getKpiForUsername(userDetails.getUsername(), from, to, period));
    }

    @GetMapping("/kpi/ranking")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<TeacherKpiRankingResponse>> getKpiRanking(
            @RequestParam(required = false, defaultValue = "monthly") String period,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        to = TeacherKpiService.defaultTo(to);
        from = TeacherKpiService.defaultFrom(period, from, to);
        return ResponseEntity.ok(ApiResponse.success(
            teacherService.getKpiRanking(period, from, to)));
    }

    @GetMapping("/{id:\\d+}")
    public ResponseEntity<ApiResponse<TeacherResponse>> getTeacherById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(teacherService.getTeacherById(id)));
    }

    @GetMapping("/{id:\\d+}/kpi")
    public ResponseEntity<TeacherKpiDto> getKpi(
            @PathVariable Long id,
            @RequestParam(required = false, defaultValue = "monthly") String period,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        to = TeacherKpiService.defaultTo(to);
        from = TeacherKpiService.defaultFrom(period, from, to);
        return ResponseEntity.ok(teacherService.getKpi(id, from, to, period));
    }

    /** Oylik trend. Ruxsat: /{id}/kpi bilan bir xil. */
    @GetMapping("/{id:\\d+}/kpi/trend")
    public ResponseEntity<ApiResponse<List<TeacherKpiTrendPointDto>>> getKpiTrend(
            @PathVariable(name = "id") Long id,
            @RequestParam(name = "months", required = false) Integer months,
            @RequestParam(name = "period", required = false, defaultValue = "monthly") String period,
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.success(
            teacherService.getKpiTrend(id, months, period, from, to)));
    }

    /** O'qituvchining o'z trendi. Ruxsat: /me/kpi bilan bir xil. */
    @GetMapping("/me/kpi/trend")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<ApiResponse<List<TeacherKpiTrendPointDto>>> myKpiTrend(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(name = "months", required = false) Integer months,
            @RequestParam(name = "period", required = false, defaultValue = "monthly") String period,
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Long teacherId = teacherService.getTeacherIdForUsername(userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.success(
            teacherService.getKpiTrend(teacherId, months, period, from, to)));
    }

    /** Oy ichida kunlik drill-down. Ruxsat: /{id}/kpi bilan bir xil. */
    @GetMapping("/{id:\\d+}/kpi/daily")
    public ResponseEntity<ApiResponse<List<TeacherKpiTrendPointDto>>> getKpiDaily(
            @PathVariable(name = "id") Long id,
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month,
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.success(
            teacherService.getKpiDaily(id, year, month, from, to)));
    }

    /** O'qituvchining o'z kunlik KPI si. Ruxsat: /me/kpi bilan bir xil. */
    @GetMapping("/me/kpi/daily")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<ApiResponse<List<TeacherKpiTrendPointDto>>> myKpiDaily(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month,
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Long teacherId = teacherService.getTeacherIdForUsername(userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.success(
            teacherService.getKpiDaily(teacherId, year, month, from, to)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<TeacherResponse>> createTeacher(@Valid @RequestBody TeacherRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Teacher created", teacherService.createTeacher(req)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<TeacherResponse>> updateTeacher(
            @PathVariable Long id, @Valid @RequestBody TeacherRequest req) {
        return ResponseEntity.ok(ApiResponse.success("Teacher updated", staffStatusService.updateTeacher(id, req)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteTeacher(@PathVariable Long id) {
        staffStatusService.deactivateTeacher(id);
        return ResponseEntity.ok(ApiResponse.success("Teacher deactivated", null));
    }

    @GetMapping("/search")
    public ResponseEntity<ApiResponse<PageResponse<TeacherResponse>>> searchTeachers(
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(teacherService.searchTeachers(q, page, size)));
    }

    @GetMapping("/stats")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStats() {
        return ResponseEntity.ok(ApiResponse.success(teacherService.getTeacherStats()));
    }

    @PostMapping("/{id:\\d+}/photo")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<TeacherResponse>> uploadPhoto(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file) {
        try {
            String filename = UUID.randomUUID() + "_teacher_" + id + ".jpg";
            String photoUrl = fileStorageService.saveImage(file, filename);
            return ResponseEntity.ok(ApiResponse.success("Rasm saqlandi",
                    teacherService.updatePhoto(id, photoUrl)));
        } catch (Exception e) {
            if (e instanceof BadRequestException) {
                throw (BadRequestException) e;
            }
            throw new BadRequestException("Rasm yuklashda xatolik");
        }
    }

    @PostMapping("/import")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<ImportResult>> importTeachersFromFile(
            @RequestParam(value = "file", required = false) MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Fayl yuklanmadi yoki bo'sh (multipart maydon nomi: file)");
        }
        return ResponseEntity.ok(ApiResponse.success(
            "Import tugadi",
            importService.importTeachers(file)));
    }

    /**
     * TEACHER rolidagi userlar uchun yetishmayotgan Teacher profillarini tiklaydi.
     * Idempotent — qayta chaqirilsa dublikat yaratmaydi.
     */
    @PostMapping("/sync-from-users")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> syncFromUsers() {
        return ResponseEntity.ok(ApiResponse.success(
            "O'qituvchi profillari sinxronlandi",
            teacherProfileSyncService.syncFromUsers()));
    }

    @GetMapping("/export")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<byte[]> exportTeachers() {
        byte[] csv = teacherService.exportTeachersCsv();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv"));
        headers.setContentDispositionFormData("attachment", "teachers.csv");
        return ResponseEntity.ok().headers(headers).body(csv);
    }
}
