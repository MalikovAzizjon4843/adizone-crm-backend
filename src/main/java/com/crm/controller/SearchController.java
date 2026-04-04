package com.crm.controller;

import com.crm.dto.response.ApiResponse;
import com.crm.repository.GroupRepository;
import com.crm.repository.StudentRepository;
import com.crm.repository.TeacherRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SearchController {

    private final StudentRepository studentRepository;
    private final TeacherRepository teacherRepository;
    private final GroupRepository groupRepository;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> globalSearch(@RequestParam String q) {
        if (q == null || q.trim().length() < 2) {
            return ResponseEntity.ok(ApiResponse.success(Map.of()));
        }

        String query = q.trim();
        String qLower = query.toLowerCase();

        Map<String, Object> results = new LinkedHashMap<>();
        Pageable limit = PageRequest.of(0, 5);

        List<Map<String, Object>> students = studentRepository
            .searchStudents(query, limit)
            .getContent()
            .stream()
            .map(s -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", s.getId());
                m.put("type", "student");
                m.put("name", s.getFirstName() + " " + s.getLastName());
                m.put("subtitle", s.getPhone());
                m.put("url", "/students/student-details/" + s.getId());
                m.put("photoUrl", s.getPhotoUrl());
                return m;
            }).collect(Collectors.toList());

        List<Map<String, Object>> teachers = teacherRepository
            .findByIsActiveTrue()
            .stream()
            .filter(t -> {
                String full = (t.getFirstName() + " " + t.getLastName()).toLowerCase();
                return full.contains(qLower)
                    || (t.getPhone() != null && t.getPhone().contains(query));
            })
            .limit(5)
            .map(t -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", t.getId());
                m.put("type", "teacher");
                m.put("name", t.getFirstName() + " " + t.getLastName());
                m.put("subtitle", t.getSubjectSpecialization());
                m.put("url", "/teachers/teacher-details/" + t.getId());
                m.put("photoUrl", t.getPhotoUrl());
                return m;
            }).collect(Collectors.toList());

        List<Map<String, Object>> groups = groupRepository
            .findAll()
            .stream()
            .filter(g -> g.getGroupName() != null
                && g.getGroupName().toLowerCase().contains(qLower))
            .limit(5)
            .map(g -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", g.getId());
                m.put("type", "group");
                m.put("name", g.getGroupName());
                m.put("subtitle", g.getCourse() != null ? g.getCourse().getCourseName() : null);
                m.put("url", "/groups/" + g.getId());
                return m;
            }).collect(Collectors.toList());

        results.put("students", students);
        results.put("teachers", teachers);
        results.put("groups", groups);
        results.put("total", students.size() + teachers.size() + groups.size());

        return ResponseEntity.ok(ApiResponse.success(results));
    }
}
