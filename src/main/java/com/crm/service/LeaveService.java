package com.crm.service;

import com.crm.dto.request.LeaveSubmitRequest;
import com.crm.dto.response.LeaveResponse;
import com.crm.dto.response.PageResponse;
import com.crm.entity.Leave;
import com.crm.entity.User;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.LeaveRepository;
import com.crm.repository.TeacherRepository;
import com.crm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LeaveService {

    private final LeaveRepository leaveRepository;
    private final UserRepository userRepository;
    private final TeacherRepository teacherRepository;

    @Transactional(readOnly = true)
    public PageResponse<LeaveResponse> getAllLeaves(int page, int size, String status) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Leave> p = (status == null || status.isBlank())
            ? leaveRepository.findAll(pageable)
            : leaveRepository.findByStatus(status, pageable);
        return buildPage(p, page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<LeaveResponse> getLeavesByTeacher(Long teacherId, int page, int size) {
        var teacher = teacherRepository.findById(teacherId)
            .orElseThrow(() -> new ResourceNotFoundException("Teacher", teacherId));
        if (teacher.getUser() == null) {
            return PageResponse.<LeaveResponse>builder()
                .content(List.of())
                .pageNumber(page).pageSize(size)
                .totalElements(0).totalPages(0).last(true)
                .build();
        }
        return getLeavesByRequester(teacher.getUser().getId(), page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<LeaveResponse> getLeavesByRequester(Long requesterId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Leave> p = leaveRepository.findByRequesterId(requesterId, pageable);
        return buildPage(p, page, size);
    }

    @Transactional(readOnly = true)
    public LeaveResponse getLeaveById(Long id) {
        return toResponse(findById(id));
    }

    @Transactional
    public LeaveResponse submitLeave(LeaveSubmitRequest request) {
        User requester = userRepository.findById(request.getRequesterId())
            .orElseThrow(() -> new ResourceNotFoundException("User", request.getRequesterId()));

        Leave leave = Leave.builder()
            .requester(requester)
            .leaveType(request.getLeaveType())
            .fromDate(request.getFromDate())
            .toDate(request.getToDate())
            .reason(request.getReason())
            .status("PENDING")
            .build();

        return toResponse(leaveRepository.save(leave));
    }

    @Transactional
    public LeaveResponse approveOrReject(Long id, Map<String, Object> body) {
        Leave leave = findById(id);
        String status = body.get("status").toString();
        leave.setStatus(status);

        if ("REJECTED".equals(status) && body.get("reason") != null) {
            String note = "[Rad etish] " + body.get("reason").toString();
            leave.setReason(leave.getReason() != null && !leave.getReason().isBlank()
                ? leave.getReason() + "\n" + note : note);
        }

        if (body.containsKey("approvedById")) {
            Long approverId = Long.valueOf(body.get("approvedById").toString());
            User approver = userRepository.findById(approverId)
                .orElseThrow(() -> new ResourceNotFoundException("User", approverId));
            leave.setApprovedBy(approver);
            leave.setApprovedAt(LocalDateTime.now());
        }

        return toResponse(leaveRepository.save(leave));
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
        return LeaveResponse.builder()
            .id(l.getId()).uuid(l.getUuid())
            .requesterId(l.getRequester().getId())
            .requesterName(l.getRequester().getUsername())
            .leaveType(l.getLeaveType())
            .fromDate(l.getFromDate()).toDate(l.getToDate())
            .reason(l.getReason()).status(l.getStatus())
            .approvedById(l.getApprovedBy() != null ? l.getApprovedBy().getId() : null)
            .approvedByName(l.getApprovedBy() != null ? l.getApprovedBy().getUsername() : null)
            .approvedAt(l.getApprovedAt()).createdAt(l.getCreatedAt())
            .build();
    }
}
