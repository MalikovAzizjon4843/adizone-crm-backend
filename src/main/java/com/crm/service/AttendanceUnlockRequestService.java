package com.crm.service;

import com.crm.dto.request.AttendanceUnlockApproveDto;
import com.crm.dto.request.AttendanceUnlockCreateDto;
import com.crm.dto.request.BonusPenaltyCreateDto;
import com.crm.dto.response.AttendanceUnlockResponseDto;
import com.crm.entity.*;
import com.crm.entity.enums.BonusPenaltyKind;
import com.crm.entity.enums.BonusTargetType;
import com.crm.entity.enums.UnlockRequestStatus;
import com.crm.exception.BadRequestException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AttendanceUnlockRequestService {

    private final AttendanceUnlockRequestRepository attendanceUnlockRequestRepository;
    private final TeacherRepository teacherRepository;
    private final GroupRepository groupRepository;
    private final UserRepository userRepository;
    private final BonusPenaltyService bonusPenaltyService;

    @Transactional
    public AttendanceUnlockResponseDto createRequest(AttendanceUnlockCreateDto dto) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new ResourceNotFoundException("User not found with username: " + username));

        Teacher teacher = teacherRepository.findByUser_Id(user.getId())
            .orElseThrow(() -> new BadRequestException("Sizning profilingiz o'qituvchi sifatida topilmadi"));

        Group group = groupRepository.findById(dto.getGroupId())
            .orElseThrow(() -> new ResourceNotFoundException("Group", dto.getGroupId()));

        boolean existsPending = attendanceUnlockRequestRepository.existsByTeacherIdAndGroupIdAndAttendanceDateAndStatus(
            teacher.getId(), group.getId(), dto.getAttendanceDate(), UnlockRequestStatus.PENDING
        );
        if (existsPending) {
            throw new BadRequestException("So'rov allaqachon yuborilgan");
        }

        AttendanceUnlockRequest req = AttendanceUnlockRequest.builder()
            .teacher(teacher)
            .group(group)
            .attendanceDate(dto.getAttendanceDate())
            .status(UnlockRequestStatus.PENDING)
            .teacherNote(dto.getNote())
            .build();

        return toResponseDto(attendanceUnlockRequestRepository.save(req));
    }

    @Transactional(readOnly = true)
    public List<AttendanceUnlockResponseDto> getPendingRequests() {
        return attendanceUnlockRequestRepository.findByStatusOrderByCreatedAtDesc(UnlockRequestStatus.PENDING)
            .stream()
            .map(this::toResponseDto)
            .toList();
    }

    @Transactional(readOnly = true)
    public List<AttendanceUnlockResponseDto> getMyRequests() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new ResourceNotFoundException("User not found with username: " + username));

        Teacher teacher = teacherRepository.findByUser_Id(user.getId())
            .orElseThrow(() -> new BadRequestException("Sizning profilingiz o'qituvchi sifatida topilmadi"));

        return attendanceUnlockRequestRepository.findByTeacherIdOrderByCreatedAtDesc(teacher.getId())
            .stream()
            .map(this::toResponseDto)
            .toList();
    }

    @Transactional
    public AttendanceUnlockResponseDto approveRequest(Long id, AttendanceUnlockApproveDto dto) {
        AttendanceUnlockRequest req = attendanceUnlockRequestRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("AttendanceUnlockRequest", id));

        if (req.getStatus() != UnlockRequestStatus.PENDING) {
            throw new BadRequestException("So'rov allaqachon ko'rib chiqilgan");
        }

        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User admin = userRepository.findByUsername(username)
            .orElseThrow(() -> new ResourceNotFoundException("User not found with username: " + username));

        req.setStatus(UnlockRequestStatus.APPROVED);
        req.setReviewedBy(admin);
        req.setReviewedAt(LocalDateTime.now());
        AttendanceUnlockRequest saved = attendanceUnlockRequestRepository.save(req);

        if (dto != null && dto.getPenaltyAmount() != null && dto.getPenaltyAmount().compareTo(BigDecimal.ZERO) > 0) {
            BonusPenaltyCreateDto bpDto = new BonusPenaltyCreateDto();
            bpDto.setKind(BonusPenaltyKind.PENALTY);
            bpDto.setTargetType(BonusTargetType.TEACHER);
            bpDto.setTeacherId(saved.getTeacher().getId());
            bpDto.setAmount(dto.getPenaltyAmount());
            bpDto.setReason(dto.getPenaltyReason() != null && !dto.getPenaltyReason().isBlank() 
                ? dto.getPenaltyReason() 
                : "Davomatni kech kiritish uchun jarima");
            bpDto.setEffectiveDate(LocalDate.now());
            bonusPenaltyService.create(bpDto);
        }

        return toResponseDto(saved);
    }

    @Transactional
    public AttendanceUnlockResponseDto rejectRequest(Long id) {
        AttendanceUnlockRequest req = attendanceUnlockRequestRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("AttendanceUnlockRequest", id));

        if (req.getStatus() != UnlockRequestStatus.PENDING) {
            throw new BadRequestException("So'rov allaqachon ko'rib chiqilgan");
        }

        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User admin = userRepository.findByUsername(username)
            .orElseThrow(() -> new ResourceNotFoundException("User not found with username: " + username));

        req.setStatus(UnlockRequestStatus.REJECTED);
        req.setReviewedBy(admin);
        req.setReviewedAt(LocalDateTime.now());
        return toResponseDto(attendanceUnlockRequestRepository.save(req));
    }

    @Transactional(readOnly = true)
    public long getPendingRequestsCount() {
        return attendanceUnlockRequestRepository.countByStatus(UnlockRequestStatus.PENDING);
    }

    private AttendanceUnlockResponseDto toResponseDto(AttendanceUnlockRequest req) {
        return AttendanceUnlockResponseDto.builder()
            .id(req.getId())
            .teacherId(req.getTeacher() != null ? req.getTeacher().getId() : null)
            .teacherName(req.getTeacher() != null ? req.getTeacher().getFirstName() + " " + req.getTeacher().getLastName() : null)
            .groupId(req.getGroup() != null ? req.getGroup().getId() : null)
            .groupName(req.getGroup() != null ? req.getGroup().getGroupName() : null)
            .attendanceDate(req.getAttendanceDate())
            .status(req.getStatus())
            .teacherNote(req.getTeacherNote())
            .reviewedById(req.getReviewedBy() != null ? req.getReviewedBy().getId() : null)
            .reviewedByName(req.getReviewedBy() != null ? req.getReviewedBy().getFirstName() + " " + req.getReviewedBy().getLastName() : null)
            .reviewedAt(req.getReviewedAt())
            .createdAt(req.getCreatedAt())
            .build();
    }
}
