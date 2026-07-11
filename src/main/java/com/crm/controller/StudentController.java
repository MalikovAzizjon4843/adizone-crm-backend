package com.crm.controller;


import com.crm.dto.request.PaymentStartDateRequest;
import com.crm.dto.request.StudentRequest;
import com.crm.dto.request.TransferGroupRequest;
import com.crm.dto.response.*;
import com.crm.entity.enums.StudentStatus;
import com.crm.exception.BadRequestException;
import com.crm.service.FileStorageService;
import com.crm.service.ImportService;
import com.crm.service.StudentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/students")
@RequiredArgsConstructor
public class StudentController {

    private final StudentService studentService;
    private final FileStorageService fileStorageService;
    private final ImportService importService;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<StudentResponse>>> getAllStudents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) StudentStatus status) {
        return ResponseEntity.ok(ApiResponse.success(
            studentService.getAllStudents(page, size, search, status)));
    }

    @GetMapping("/{id}/groups")
    public ResponseEntity<ApiResponse<List<StudentDetailResponse.GroupSummary>>> getStudentGroupHistory(
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(studentService.getStudentGroupHistory(id)));
    }

    @GetMapping("/archived")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<List<StudentResponse>>> getArchivedStudents() {
        return ResponseEntity.ok(ApiResponse.success(studentService.getArchivedStudents()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<StudentDetailResponse>> getStudentById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(studentService.getStudentById(id)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<StudentResponse>> createStudent(
            @Valid @RequestBody StudentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Student created", studentService.createStudent(request)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<StudentResponse>> updateStudent(
            @PathVariable Long id, @Valid @RequestBody StudentRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Student updated", studentService.updateStudent(id, request)));
    }

    @PostMapping("/{id}/transfer-group")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<StudentDetailResponse>> transferGroup(
            @PathVariable Long id,
            @Valid @RequestBody TransferGroupRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Guruh o'zgartirildi",
            studentService.transferGroup(id, request)));
    }

    @PatchMapping("/{id}/payment-start-date")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT')")
    public ResponseEntity<ApiResponse<StudentDetailResponse>> updatePaymentStartDate(
            @PathVariable Long id,
            @Valid @RequestBody PaymentStartDateRequest request) {
        return ResponseEntity.ok(ApiResponse.success("To'lov boshlanish sanasi yangilandi",
            studentService.updatePaymentStartDate(
                id, request.getPaymentStartDate(), request.getIsTrial())));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteStudent(@PathVariable Long id) {
        studentService.deleteStudent(id);
        return ResponseEntity.ok(ApiResponse.success("Student deactivated", null));
    }

    @GetMapping("/search")
    public ResponseEntity<ApiResponse<PageResponse<StudentResponse>>> searchStudents(
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(studentService.searchStudents(q, page, size)));
    }

    @PostMapping("/import")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<ImportResult>> importStudentsFromFile(
            @RequestParam(value = "file", required = false) MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Fayl yuklanmadi yoki bo'sh (multipart maydon nomi: file)");
        }
        return ResponseEntity.ok(ApiResponse.success(
            "Import tugadi",
            importService.importStudents(file)));
    }

    @GetMapping("/export")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<byte[]> exportStudents() {
        byte[] csv = studentService.exportStudentsCsv();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv"));
        headers.setContentDispositionFormData("attachment", "students.csv");
        return ResponseEntity.ok().headers(headers).body(csv);
    }

    @PostMapping("/{id:\\d+}/photo")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<StudentResponse>> uploadPhoto(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file) {
        try {
            String filename = UUID.randomUUID() + "_student_" + id + ".jpg";
            String photoUrl = fileStorageService.saveImage(file, filename);
            return ResponseEntity.ok(ApiResponse.success("Rasm saqlandi",
                    studentService.updatePhoto(id, photoUrl)));
        } catch (Exception e) {
            if (e instanceof BadRequestException) {
                throw (BadRequestException) e;
            }
            throw new BadRequestException("Rasm yuklashda xatolik");
        }
    }

    @GetMapping("/stats")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStats() {
        return ResponseEntity.ok(ApiResponse.success(studentService.getStudentStats()));
    }

    // Ketgan o'quvchilar (LEFT, GRADUATED)
    @GetMapping("/left")
    public ResponseEntity<ApiResponse<List<StudentResponse>>> getLeftStudents() {
        return ResponseEntity.ok(ApiResponse.success(
            studentService.getStudentsByStatus(
                List.of("LEFT", "GRADUATED"))));
    }

    // Probniy o'quvchilar (TRIAL payment status)
    @GetMapping("/trial")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getTrialStudents() {
        return ResponseEntity.ok(ApiResponse.success(
            studentService.getTrialStudents()));
    }

    // Student status history
    @GetMapping("/{id}/history")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getHistory(
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(
            studentService.getStudentHistory(id)));
    }
}
