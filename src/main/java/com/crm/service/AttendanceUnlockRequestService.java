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
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
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

    /** Avvalgi derived metodlardagi {@code OrderByCreatedAtDesc} bilan bir xil. */
    private static final Sort NEWEST_FIRST = Sort.by("createdAt").descending();

    private final AttendanceUnlockRequestRepository attendanceUnlockRequestRepository;
    private final GroupRepository groupRepository;
    private final UserRepository userRepository;
    private final BonusPenaltyService bonusPenaltyService;
    private final TeacherAccessService teacherAccessService;

    @Transactional
    public AttendanceUnlockResponseDto createRequest(AttendanceUnlockCreateDto dto) {
        Teacher teacher = teacherAccessService.getCurrentTeacherOrThrow();

        Group group = groupRepository.findById(dto.getGroupId())
            .orElseThrow(() -> new ResourceNotFoundException("Group", dto.getGroupId()));

        teacherAccessService.assertOwnsGroup(group);

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

    /**
     * Admin ro'yxati. {@code status} berilmasa PENDING — avvalgi xulq.
     * {@code groupId} va {@code date} ixtiyoriy.
     */
    @Transactional(readOnly = true)
    public List<AttendanceUnlockResponseDto> getRequests(String status, Long groupId, LocalDate date) {
        Specification<AttendanceUnlockRequest> spec =
            buildSpec(parseStatus(status), null, groupId, date);
        return attendanceUnlockRequestRepository.findAll(spec, NEWEST_FIRST)
            .stream()
            .map(this::toResponseDto)
            .toList();
    }

    /**
     * O'qituvchining o'z so'rovlari.
     *
     * <p>{@code groupId} va {@code date} ixtiyoriy: ikkalasi ham null bo'lsa
     * avvalgidek barcha so'rovlar qaytadi. Filtrsiz ro'yxat davomat sahifasida
     * xato natija berardi — boshqa kunga yoki boshqa guruhga berilgan
     * tasdiqlangan ruxsat bugungi jurnalni ochiq deb ko'rsatardi.
     */
    @Transactional(readOnly = true)
    public List<AttendanceUnlockResponseDto> getMyRequests(Long groupId, LocalDate date) {
        Teacher teacher = teacherAccessService.getCurrentTeacherOrThrow();

        Specification<AttendanceUnlockRequest> spec =
            buildSpec(null, teacher.getId(), groupId, date);
        return attendanceUnlockRequestRepository.findAll(spec, NEWEST_FIRST)
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

    private Specification<AttendanceUnlockRequest> buildSpec(
            UnlockRequestStatus status, Long teacherId, Long groupId, LocalDate date) {
        Specification<AttendanceUnlockRequest> spec = Specification.where(null);

        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (teacherId != null) {
            spec = spec.and((root, query, cb) ->
                cb.equal(root.get("teacher").get("id"), teacherId));
        }
        if (groupId != null) {
            spec = spec.and((root, query, cb) ->
                cb.equal(root.get("group").get("id"), groupId));
        }
        if (date != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("attendanceDate"), date));
        }
        return spec;
    }

    /** Berilmasa PENDING — endpoint avval shu qiymatni qat'iy ishlatardi. */
    private UnlockRequestStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return UnlockRequestStatus.PENDING;
        }
        try {
            return UnlockRequestStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Noto'g'ri so'rov statusi: " + status);
        }
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
