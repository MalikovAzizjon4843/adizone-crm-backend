package com.crm.controller;

import com.crm.dto.request.*;
import com.crm.dto.response.*;
import com.crm.service.AcademicService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class AcademicController {

    private final AcademicService academicService;

    // ── Classes ────────────────────────────────────────────────────

    @GetMapping("/api/classes")
    public ResponseEntity<ApiResponse<PageResponse<ClassResponse>>> getAllClasses(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(academicService.getAllClasses(page, size)));
    }

    @GetMapping("/api/classes/{id}")
    public ResponseEntity<ApiResponse<ClassResponse>> getClassById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(academicService.getClassById(id)));
    }

    @PostMapping("/api/classes")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<ClassResponse>> createClass(@Valid @RequestBody ClassRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Class created", academicService.createClass(request)));
    }

    @PutMapping("/api/classes/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<ClassResponse>> updateClass(
            @PathVariable Long id, @Valid @RequestBody ClassRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Class updated", academicService.updateClass(id, request)));
    }

    @DeleteMapping("/api/classes/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteClass(@PathVariable Long id) {
        academicService.deleteClass(id);
        return ResponseEntity.ok(ApiResponse.success("Class deleted", null));
    }

    // ── Sections ───────────────────────────────────────────────────

    @GetMapping("/api/sections")
    public ResponseEntity<ApiResponse<PageResponse<SectionResponse>>> getAllSections(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(academicService.getAllSections(page, size)));
    }

    @GetMapping("/api/sections/{id}")
    public ResponseEntity<ApiResponse<SectionResponse>> getSectionById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(academicService.getSectionById(id)));
    }

    @PostMapping("/api/sections")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<SectionResponse>> createSection(@Valid @RequestBody SectionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Section created", academicService.createSection(request)));
    }

    @PutMapping("/api/sections/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<SectionResponse>> updateSection(
            @PathVariable Long id, @Valid @RequestBody SectionRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Section updated", academicService.updateSection(id, request)));
    }

    @DeleteMapping("/api/sections/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteSection(@PathVariable Long id) {
        academicService.deleteSection(id);
        return ResponseEntity.ok(ApiResponse.success("Section deleted", null));
    }

    // ── Subjects ───────────────────────────────────────────────────

    @GetMapping("/api/subjects")
    public ResponseEntity<ApiResponse<PageResponse<SubjectResponse>>> getAllSubjects(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(academicService.getAllSubjects(page, size)));
    }

    @GetMapping("/api/subjects/{id}")
    public ResponseEntity<ApiResponse<SubjectResponse>> getSubjectById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(academicService.getSubjectById(id)));
    }

    @PostMapping("/api/subjects")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<SubjectResponse>> createSubject(@Valid @RequestBody SubjectRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Subject created", academicService.createSubject(request)));
    }

    @PutMapping("/api/subjects/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<SubjectResponse>> updateSubject(
            @PathVariable Long id, @Valid @RequestBody SubjectRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Subject updated", academicService.updateSubject(id, request)));
    }

    @DeleteMapping("/api/subjects/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteSubject(@PathVariable Long id) {
        academicService.deleteSubject(id);
        return ResponseEntity.ok(ApiResponse.success("Subject deleted", null));
    }

    // ── Timetable ──────────────────────────────────────────────────
    //
    // Standart holda faqat ACTIVE/FORMING guruhlar va end_date'i o'tmaganlari (grid/by-room: ?date
    // bo'yicha, qolganlari: bugun). ?includeClosed=true — COMPLETED/CANCELLED ham (faqat SA/ADMIN, aks holda 403).

    @GetMapping("/api/timetable")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEACHER')")
    public ResponseEntity<ApiResponse<PageResponse<TimetableResponse>>> getAllTimetable(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(name = "includeClosed", defaultValue = "false") boolean includeClosed) {
        return ResponseEntity.ok(ApiResponse.success(academicService.getAllTimetable(page, size, includeClosed)));
    }

    @GetMapping("/api/timetable/group/{groupId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEACHER')")
    public ResponseEntity<ApiResponse<List<TimetableResponse>>> getTimetableByGroup(
            @PathVariable(name = "groupId") Long groupId,
            @RequestParam(name = "includeClosed", defaultValue = "false") boolean includeClosed) {
        return ResponseEntity.ok(ApiResponse.success(academicService.getTimetableByGroup(groupId, includeClosed)));
    }

    @GetMapping("/api/timetable/by-room")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEACHER')")
    public ResponseEntity<RoomTimetableDto> getByRoom(
            @RequestParam(name = "dayOfWeek", required = false) String dayOfWeek,
            @RequestParam(name = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(name = "includeClosed", defaultValue = "false") boolean includeClosed) {
        return ResponseEntity.ok(academicService.getByRoom(dayOfWeek, date, includeClosed));
    }

    @GetMapping("/api/timetable/grid")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEACHER')")
    public ResponseEntity<ApiResponse<TimetableGridResponse>> getTimetableGrid(
            @RequestParam(name = "dayOfWeek", required = false) String dayOfWeek,
            @RequestParam(name = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(name = "includeClosed", defaultValue = "false") boolean includeClosed) {
        return ResponseEntity.ok(ApiResponse.success(academicService.getTimetableGrid(dayOfWeek, date, includeClosed)));
    }

    @GetMapping("/api/timetable/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEACHER')")
    public ResponseEntity<ApiResponse<TimetableResponse>> getTimetableById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(academicService.getTimetableById(id)));
    }

    @GetMapping("/api/timetable/class/{classId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEACHER')")
    public ResponseEntity<ApiResponse<List<TimetableResponse>>> getTimetableByClass(
            @PathVariable(name = "classId") Long classId,
            @RequestParam(name = "includeClosed", defaultValue = "false") boolean includeClosed) {
        return ResponseEntity.ok(ApiResponse.success(academicService.getTimetableByClass(classId, includeClosed)));
    }

    @GetMapping("/api/timetable/teacher/{teacherId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEACHER')")
    public ResponseEntity<ApiResponse<List<TimetableResponse>>> getTimetableByTeacher(
            @PathVariable(name = "teacherId") Long teacherId,
            @RequestParam(name = "includeClosed", defaultValue = "false") boolean includeClosed) {
        return ResponseEntity.ok(ApiResponse.success(academicService.getTimetableByTeacher(teacherId, includeClosed)));
    }

    @PostMapping("/api/timetable")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<?> createTimetable(@Valid @RequestBody TimetableRequest request) {
        boolean multi = request.getDaysOfWeek() != null && !request.getDaysOfWeek().isEmpty();
        if (multi) {
            return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Timetable entries created",
                    academicService.createTimetableBatch(request)));
        }
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Timetable entry created",
                academicService.createTimetable(request)));
    }

    @PutMapping("/api/timetable/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<TimetableResponse>> updateTimetable(
            @PathVariable Long id, @Valid @RequestBody TimetableRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Timetable updated", academicService.updateTimetable(id, request)));
    }

    @DeleteMapping("/api/timetable/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteTimetable(@PathVariable Long id) {
        academicService.deleteTimetable(id);
        return ResponseEntity.ok(ApiResponse.success("Timetable entry deleted", null));
    }
}
