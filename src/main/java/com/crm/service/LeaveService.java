package com.crm.service;

import com.crm.dto.request.LeaveSubmitRequest;
import com.crm.dto.response.LeaveResponse;
import com.crm.dto.response.PageResponse;
import com.crm.entity.Leave;
import com.crm.entity.Teacher;
import com.crm.entity.User;
import com.crm.entity.enums.UserRole;
import com.crm.exception.BadRequestException;
import com.crm.exception.ForbiddenException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.LeaveRepository;
import com.crm.repository.TeacherRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class LeaveService {

    private final LeaveRepository leaveRepository;
    private final TeacherRepository teacherRepository;
    private final TeacherAccessService teacherAccessService;

    @Transactional(readOnly = true)
    public PageResponse<LeaveResponse> getAllLeaves(int page, int size, String status) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Leave> p = (status == null || status.isBlank())
            ? leaveRepository.findAll(pageable)
            : leaveRepository.findByStatus(status, pageable);
        return buildPage(p, page, size);
    }

    /**
     * O'qituvchining ta'tillari — {@code teacher_id} bo'yicha: ariza kim
     * tomonidan yuborilganidan (o'zi yoki admin) qat'i nazar.
     */
    @Transactional(readOnly = true)
    public PageResponse<LeaveResponse> getLeavesByTeacher(Long teacherId, int page, int size) {
        if (!teacherRepository.existsById(teacherId)) {
            throw new ResourceNotFoundException("Teacher", teacherId);
        }
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Leave> p = leaveRepository.findByTeacher_Id(teacherId, pageable);
        return buildPage(p, page, size);
    }

    /** TEACHER faqat o'zi yuborgan arizalarni ko'radi. */
    @Transactional(readOnly = true)
    public PageResponse<LeaveResponse> getLeavesByRequester(Long requesterId, int page, int size) {
        if (teacherAccessService.isCurrentUserTeacher()
                && !teacherAccessService.getCurrentUserOrThrow().getId().equals(requesterId)) {
            throw new ForbiddenException("Bu arizalar sizga tegishli emas");
        }
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Leave> p = leaveRepository.findByRequesterId(requesterId, pageable);
        return buildPage(p, page, size);
    }

    @Transactional(readOnly = true)
    public LeaveResponse getLeaveById(Long id) {
        Leave leave = findById(id);
        assertCanView(leave);
        return toResponse(leave);
    }

    /**
     * Ariza yuborish. Yuboruvchi ({@code requester}) — har doim joriy
     * foydalanuvchi; tanadagi {@code requesterId} e'tiborsiz (eski frontend
     * unga teacherId ni yuborardi). TEACHER faqat o'zi uchun yuboradi —
     * {@code teacherId} ham e'tiborsiz; SUPER_ADMIN/ADMIN o'qituvchini
     * {@code teacherId} bilan tanlaydi.
     */
    @Transactional
    public LeaveResponse submitLeave(LeaveSubmitRequest request) {
        User requester = teacherAccessService.getCurrentUserOrThrow();

        Teacher teacher;
        if (requester.getRole() == UserRole.TEACHER) {
            teacher = teacherAccessService.getCurrentTeacherOrThrow();
        } else {
            if (request.getTeacherId() == null) {
                throw new BadRequestException("O'qituvchi tanlanmagan (teacherId majburiy)");
            }
            teacher = teacherRepository.findById(request.getTeacherId())
                .orElseThrow(() -> new ResourceNotFoundException("Teacher", request.getTeacherId()));
        }

        Leave leave = new Leave();
        leave.setTeacher(teacher);
        leave.setRequester(requester);

        leave.setLeaveType(request.getLeaveType());
        leave.setFromDate(request.getFromDate());
        leave.setToDate(request.getToDate());
        leave.setReason(request.getReason());
        leave.setStatus("PENDING");

        return toResponse(leaveRepository.save(leave));
    }

    /**
     * Tasdiqlash / rad etish. Tasdiqlovchi — joriy foydalanuvchi; tanadagi
     * {@code approvedById} e'tiborsiz.
     */
    @Transactional
    public LeaveResponse approveOrReject(Long id, Map<String, Object> body) {
        Leave leave = findById(id);
        Object rawStatus = body.get("status");
        if (rawStatus == null || rawStatus.toString().isBlank()) {
            throw new BadRequestException("status majburiy");
        }
        String status = rawStatus.toString();
        leave.setStatus(status);

        if ("REJECTED".equals(status) && body.get("reason") != null) {
            String note = "[Rad etish] " + body.get("reason").toString();
            leave.setReason(leave.getReason() != null && !leave.getReason().isBlank()
                ? leave.getReason() + "\n" + note : note);
        }

        leave.setApprovedBy(teacherAccessService.getCurrentUserOrThrow());
        leave.setApprovedAt(LocalDateTime.now());

        return toResponse(leaveRepository.save(leave));
    }

    /** TEACHER — faqat o'zi yuborgan yoki o'zi haqidagi ariza. */
    private void assertCanView(Leave leave) {
        if (!teacherAccessService.isCurrentUserTeacher()) {
            return;
        }
        Long userId = teacherAccessService.getCurrentUserOrThrow().getId();
        boolean ownRequest = leave.getRequester() != null
            && userId.equals(leave.getRequester().getId());
        boolean aboutMe = leave.getTeacher() != null && leave.getTeacher().getUser() != null
            && userId.equals(leave.getTeacher().getUser().getId());
        if (!ownRequest && !aboutMe) {
            throw new ForbiddenException("Bu ariza sizga tegishli emas");
        }
    }

    @Transactional
    public void deleteLeave(Long id) {
        leaveRepository.delete(findById(id));
    }

    @Transactional(readOnly = true)
    public List<LeaveResponse> getPendingLeaves() {
        return leaveRepository.findByStatus("PENDING").stream()
            .map(this::toResponse).collect(Collectors.toList());
    }

    public Leave findById(Long id) {
        return leaveRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("LeaveRequest", id));
    }

    private PageResponse<LeaveResponse> buildPage(Page<Leave> p, int page, int size) {
        return PageResponse.<LeaveResponse>builder()
            .content(p.getContent().stream().map(this::toResponse).collect(Collectors.toList()))
            .pageNumber(page).pageSize(size)
            .totalElements(p.getTotalElements()).totalPages(p.getTotalPages()).last(p.isLast())
            .build();
    }

    private LeaveResponse toResponse(Leave l) {
        LeaveResponse response = LeaveResponse.builder()
            .id(l.getId()).uuid(l.getUuid())
            .requesterId(l.getRequester() != null ? l.getRequester().getId() : null)
            .requesterName(l.getRequester() != null ? l.getRequester().getUsername() : null)
            .leaveType(l.getLeaveType())
            .fromDate(l.getFromDate()).toDate(l.getToDate())
            .reason(l.getReason()).status(l.getStatus())
            .approvedById(l.getApprovedBy() != null ? l.getApprovedBy().getId() : null)
            .approvedByName(l.getApprovedBy() != null ? l.getApprovedBy().getUsername() : null)
            .approvedAt(l.getApprovedAt()).createdAt(l.getCreatedAt())
            .build();
            
        if (l.getTeacher() != null) {
            response.setTeacherName(l.getTeacher().getFirstName() + " " + l.getTeacher().getLastName());
        }
        
        return response;
    }
}
