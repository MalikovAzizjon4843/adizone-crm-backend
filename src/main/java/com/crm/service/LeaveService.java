package com.crm.service;

import com.crm.dto.request.LeaveSubmitRequest;
import com.crm.dto.response.LeaveResponse;
import com.crm.dto.response.PageResponse;
import com.crm.entity.Leave;
import com.crm.entity.Teacher;
import com.crm.entity.User;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.LeaveRepository;
import com.crm.repository.TeacherRepository;
import com.crm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
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
        Leave leave = new Leave();
        
        Teacher teacher = teacherRepository.findById(request.getTeacherId())
            .orElseThrow(() -> new ResourceNotFoundException("Teacher", request.getTeacherId()));
        leave.setTeacher(teacher);

        if (request.getRequesterId() != null) {
            userRepository.findById(request.getRequesterId()).ifPresent(leave::setRequester);
        } else if (teacher.getUser() != null) {
            leave.setRequester(teacher.getUser());
        }

        leave.setLeaveType(request.getLeaveType());
        leave.setFromDate(request.getFromDate());
        leave.setToDate(request.getToDate());
        leave.setReason(request.getReason());
        leave.setStatus("PENDING");

        return toResponse(leaveRepository.save(leave));
    }

    private User resolveRequester(LeaveSubmitRequest request) {
        if (request.getRequesterId() != null) {
            return userRepository.findById(request.getRequesterId()).orElse(null);
        }
        if (request.getTeacherId() != null) {
            Teacher teacher = teacherRepository.findById(request.getTeacherId())
                .orElseThrow(() -> new ResourceNotFoundException("Teacher", request.getTeacherId()));
            if (teacher.getUser() != null) {
                return teacher.getUser();
            }
            return userRepository.findAll().stream()
                .filter(u -> namesMatch(u, teacher))
                .findFirst()
                .orElse(null);
        }
        return null;
    }

    private static boolean namesMatch(User u, Teacher t) {
        if (u == null || t == null) {
            return false;
        }
        return Objects.equals(normalize(u.getFirstName()), normalize(t.getFirstName()))
            && Objects.equals(normalize(u.getLastName()), normalize(t.getLastName()));
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase();
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
