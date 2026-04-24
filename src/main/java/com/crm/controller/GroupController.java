package com.crm.controller;

import com.crm.dto.request.GroupRequest;
import com.crm.dto.request.StudentGroupRequest;
import com.crm.dto.response.ApiResponse;
import com.crm.dto.response.GroupResponse;
import com.crm.dto.response.SuspendedStudentResponse;
import com.crm.entity.enums.GroupStatus;
import com.crm.service.GroupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
public class GroupController {

    private final GroupService groupService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<GroupResponse>>> getAllGroups(
            @RequestParam(required = false) GroupStatus status) {
        return ResponseEntity.ok(ApiResponse.success(groupService.getAllGroups(status)));
    }

    @GetMapping("/{id}/schedule")
    public ResponseEntity<ApiResponse<List<GroupResponse.ScheduleDayResponse>>> getSchedule(
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(groupService.getSchedule(id)));
    }

    @GetMapping("/{id}/suspended-students")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<List<SuspendedStudentResponse>>> getSuspendedStudents(
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(groupService.getSuspendedStudents(id)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<GroupResponse>> getGroupById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(groupService.getGroupById(id)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<GroupResponse>> createGroup(@Valid @RequestBody GroupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Group created", groupService.createGroup(request)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<GroupResponse>> updateGroup(
            @PathVariable Long id, @Valid @RequestBody GroupRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Group updated", groupService.updateGroup(id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteGroup(@PathVariable Long id) {
        groupService.deleteGroup(id);
        return ResponseEntity.ok(ApiResponse.success("Group cancelled", null));
    }

    @PostMapping("/students")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<String>> addStudentToGroup(
            @Valid @RequestBody StudentGroupRequest request) {
        groupService.addStudentToGroup(request);
        String message = "O'quvchi guruhga qo'shildi";
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success(message, "OK"));
    }

    @DeleteMapping("/{groupId}/students/{studentId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<Void>> removeStudentFromGroup(
            @PathVariable Long groupId, @PathVariable Long studentId) {
        groupService.removeStudentFromGroup(studentId, groupId);
        return ResponseEntity.ok(ApiResponse.success("Student removed from group", null));
    }
}
