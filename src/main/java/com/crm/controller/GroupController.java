package com.crm.controller;

import com.crm.dto.request.GroupPromoteRequest;
import com.crm.dto.request.GroupRequest;
import com.crm.dto.request.RemoveStudentRequest;
import com.crm.dto.request.StudentGroupRequest;
import com.crm.dto.request.StudentCreateAndAddRequest;
import com.crm.billing.GroupEndDateService;
import com.crm.dto.response.ApiResponse;
import com.crm.dto.response.GroupEndDateDtos;
import com.crm.dto.response.GroupLessonDaysResponse;
import com.crm.dto.response.GroupResponse;
import com.crm.dto.response.SuspendedStudentResponse;
import com.crm.dto.response.StudentResponse;
import com.crm.entity.enums.GroupStatus;
import com.crm.dto.response.GroupPromoteDtos;
import com.crm.service.GroupPromotionService;
import com.crm.service.GroupService;
import com.crm.service.StudentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
public class GroupController {

    private final GroupService groupService;
    private final StudentService studentService;
    private final GroupPromotionService promotionService;
    private final GroupEndDateService groupEndDateService;

    /** Guruhga ko'chirish oldindan ko'rish (phase6-api §5) — hech narsa yozmaydi. */
    @PostMapping("/{fromId}/promote/preview")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<GroupPromoteDtos.Preview>> promotePreview(
            @PathVariable Long fromId, @Valid @RequestBody GroupPromoteRequest request) {
        return ResponseEntity.ok(ApiResponse.success(promotionService.preview(fromId, request)));
    }

    /**
     * Guruhga ko'chirish — hammasi yoki hech biri. {@code Idempotency-Key}: takror so'rov o'sha natija,
     * 200 + {@code X-Idempotent-Replay: true}.
     */
    @PostMapping("/{fromId}/promote")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<GroupPromoteDtos.Result>> promote(
            @PathVariable Long fromId, @Valid @RequestBody GroupPromoteRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        var replay = promotionService.replay(fromId, request, idempotencyKey);
        if (replay.isPresent()) {
            return ResponseEntity.ok().header("X-Idempotent-Replay", "true")
                .body(ApiResponse.success("Allaqachon ko'chirilgan", replay.get()));
        }
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Ko'chirildi", promotionService.promote(fromId, request, idempotencyKey)));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT','TEACHER')")
    public ResponseEntity<ApiResponse<List<GroupResponse>>> getAllGroups(
            @RequestParam(required = false) GroupStatus status) {
        return ResponseEntity.ok(ApiResponse.success(groupService.getAllGroups(status)));
    }

    @GetMapping("/{id}/schedule")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT','TEACHER')")
    public ResponseEntity<ApiResponse<List<GroupResponse.ScheduleDayResponse>>> getSchedule(
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(groupService.getSchedule(id)));
    }

    @GetMapping("/{id}/lesson-days")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT','TEACHER')")
    public ResponseEntity<ApiResponse<GroupLessonDaysResponse>> getLessonDays(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(groupService.getLessonDays(id)));
    }

    @GetMapping("/{id}/suspended-students")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<List<SuspendedStudentResponse>>> getSuspendedStudents(
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(groupService.getSuspendedStudents(id)));
    }

    /**
     * Diqqat talab qiladigan guruhlar (billing-v2 R3): ACTIVE/FORMING, {@code end_date ≤ bugun} yoki boshlanishdan
     * oldin, va ularda R3 sababli davri yozilmay qolgan yozilmalar soni. Faqat o'qiydi.
     */
    @GetMapping(value = "/attention")
    @PreAuthorize(value = "hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<GroupEndDateDtos.Attention>> getAttention() {
        return ResponseEntity.ok(ApiResponse.success(groupEndDateService.attention()));
    }

    /**
     * {@code end_date} o'zgarishi ta'siri: har ochiq yozilma uchun yoziladigan davrlar, balans oldin → keyin,
     * qarzdorlik, yangi keyingi to'lov sanasi. Faqat o'qiydi. {@code endDate} berilmasa — tugash sanasisiz.
     */
    @GetMapping(value = "/{id}/end-date-impact")
    @PreAuthorize(value = "hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<GroupEndDateDtos.Impact>> getEndDateImpact(
            @PathVariable(name = "id") Long id,
            @RequestParam(name = "endDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(ApiResponse.success(groupEndDateService.impact(id, endDate)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT','TEACHER')")
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

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<GroupResponse>> updateStatus(
            @PathVariable Long id,
            @RequestParam String status) {
        return ResponseEntity.ok(ApiResponse.success(
            "Status yangilandi", groupService.updateStatus(id, status)));
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

    @PostMapping("/{groupId}/remove-student")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<String>> removeStudentWithReason(
            @PathVariable Long groupId,
            @RequestBody RemoveStudentRequest request) {
        groupService.removeStudentFromGroup(
            groupId, request.getStudentId(),
            request.getReason(), request.getNotes(), request.getReasonCode());
        return ResponseEntity.ok(
            ApiResponse.success("O'quvchi guruhdan chiqarildi", "OK"));
    }

    @PostMapping("/{groupId}/students/create-and-add")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<StudentResponse>> createAndAddStudent(
            @PathVariable Long groupId,
            @Valid @RequestBody StudentCreateAndAddRequest request) {
        StudentResponse response = studentService.createAndAddStudentToGroup(groupId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("O'quvchi yaratildi va guruhga qo'shildi", response));
    }
}
