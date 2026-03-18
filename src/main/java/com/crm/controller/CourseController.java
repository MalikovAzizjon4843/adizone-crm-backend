package com.crm.controller;
import com.crm.dto.request.CourseRequest;
import com.crm.dto.response.*;
import com.crm.service.CourseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/courses")
@RequiredArgsConstructor
public class CourseController {
    private final CourseService courseService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<CourseResponse>>> getAllCourses(
            @RequestParam(defaultValue = "true") boolean activeOnly) {
        return ResponseEntity.ok(ApiResponse.success(courseService.getAllCourses(activeOnly)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<CourseResponse>> getCourseById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(courseService.getCourseById(id)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<CourseResponse>> createCourse(@Valid @RequestBody CourseRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Course created", courseService.createCourse(req)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<CourseResponse>> updateCourse(@PathVariable Long id, @Valid @RequestBody CourseRequest req) {
        return ResponseEntity.ok(ApiResponse.success("Course updated", courseService.updateCourse(id, req)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteCourse(@PathVariable Long id) {
        courseService.deleteCourse(id);
        return ResponseEntity.ok(ApiResponse.success("Course deleted", null));
    }
}
