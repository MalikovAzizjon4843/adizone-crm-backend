package com.crm.controller;

import com.crm.dto.request.*;
import com.crm.dto.response.*;
import com.crm.service.AcademicService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

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

    // ── Classrooms ─────────────────────────────────────────────────

    @GetMapping("/api/classrooms")
    public ResponseEntity<ApiResponse<PageResponse<ClassroomResponse>>> getAllClassrooms(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(academicService.getAllClassrooms(page, size)));
    }

    @GetMapping("/api/classrooms/{id}")
    public ResponseEntity<ApiResponse<ClassroomResponse>> getClassroomById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(academicService.getClassroomById(id)));
    }

    @PostMapping("/api/classrooms")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<ClassroomResponse>> createClassroom(@Valid @RequestBody ClassroomRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Classroom created", academicService.createClassroom(request)));
    }

    @PutMapping("/api/classrooms/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<ClassroomResponse>> updateClassroom(
            @PathVariable Long id, @Valid @RequestBody ClassroomRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Classroom updated", academicService.updateClassroom(id, request)));
    }

    @DeleteMapping("/api/classrooms/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteClassroom(@PathVariable Long id) {
        academicService.deleteClassroom(id);
        return ResponseEntity.ok(ApiResponse.success("Classroom deleted", null));
    }

    // ── Timetable ──────────────────────────────────────────────────

    @GetMapping("/api/timetable")
    public ResponseEntity<ApiResponse<PageResponse<TimetableResponse>>> getAllTimetable(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(academicService.getAllTimetable(page, size)));
    }

    @GetMapping("/api/timetable/{id}")
    public ResponseEntity<ApiResponse<TimetableResponse>> getTimetableById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(academicService.getTimetableById(id)));
    }

    @GetMapping("/api/timetable/class/{classId}")
    public ResponseEntity<ApiResponse<List<TimetableResponse>>> getTimetableByClass(@PathVariable Long classId) {
        return ResponseEntity.ok(ApiResponse.success(academicService.getTimetableByClass(classId)));
    }

    @GetMapping("/api/timetable/teacher/{teacherId}")
    public ResponseEntity<ApiResponse<List<TimetableResponse>>> getTimetableByTeacher(@PathVariable Long teacherId) {
        return ResponseEntity.ok(ApiResponse.success(academicService.getTimetableByTeacher(teacherId)));
    }

    @PostMapping("/api/timetable")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<TimetableResponse>> createTimetable(@Valid @RequestBody TimetableRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Timetable entry created", academicService.createTimetable(request)));
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
