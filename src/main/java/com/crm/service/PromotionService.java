package com.crm.service;

import com.crm.dto.request.BulkPromoteRequest;
import com.crm.dto.request.PromotionRequest;
import com.crm.dto.response.PageResponse;
import com.crm.dto.response.PromotionResponse;
import com.crm.entity.*;
import com.crm.exception.BadRequestException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PromotionService {

    private final PromotionRepository promotionRepository;
    private final StudentRepository studentRepository;
    private final ClassRepository classRepository;
    private final SectionRepository sectionRepository;
    private final UserRepository userRepository;
    private final GroupRepository groupRepository;
    private final StudentGroupRepository studentGroupRepository;

    @Transactional(readOnly = true)
    public PageResponse<PromotionResponse> getAllPromotions(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Promotion> p = promotionRepository.findAll(pageable);
        return PageResponse.<PromotionResponse>builder()
            .content(p.getContent().stream().map(this::toResponse).collect(Collectors.toList()))
            .pageNumber(page).pageSize(size)
            .totalElements(p.getTotalElements()).totalPages(p.getTotalPages()).last(p.isLast())
            .build();
    }

    @Transactional(readOnly = true)
    public PromotionResponse getPromotionById(Long id) {
        return toResponse(findById(id));
    }

    @Transactional(readOnly = true)
    public List<PromotionResponse> getPromotionsByStudent(Long studentId) {
        return promotionRepository.findByStudentId(studentId).stream()
            .map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional
    public PromotionResponse createPromotion(PromotionRequest request) {
        Student student = studentRepository.findById(request.getStudentId())
            .orElseThrow(() -> new ResourceNotFoundException("Student", request.getStudentId()));

        Promotion promotion = Promotion.builder()
            .student(student)
            .fromAcademicYear(request.getFromAcademicYear())
            .toAcademicYear(request.getToAcademicYear())
            .promotionDate(request.getPromotionDate() != null ? request.getPromotionDate() : LocalDate.now())
            .remarks(request.getRemarks())
            .build();

        if (request.getFromClassId() != null)
            promotion.setFromClass(classRepository.findById(request.getFromClassId())
                .orElseThrow(() -> new ResourceNotFoundException("Class", request.getFromClassId())));
        if (request.getToClassId() != null)
            promotion.setToClass(classRepository.findById(request.getToClassId())
                .orElseThrow(() -> new ResourceNotFoundException("Class", request.getToClassId())));
        if (request.getFromSectionId() != null)
            promotion.setFromSection(sectionRepository.findById(request.getFromSectionId())
                .orElseThrow(() -> new ResourceNotFoundException("Section", request.getFromSectionId())));
        if (request.getToSectionId() != null)
            promotion.setToSection(sectionRepository.findById(request.getToSectionId())
                .orElseThrow(() -> new ResourceNotFoundException("Section", request.getToSectionId())));
        if (request.getPromotedById() != null)
            promotion.setPromotedBy(userRepository.findById(request.getPromotedById())
                .orElseThrow(() -> new ResourceNotFoundException("User", request.getPromotedById())));

        return toResponse(promotionRepository.save(promotion));
    }

    @Transactional
    public Map<String, Object> bulkPromote(BulkPromoteRequest request) {
        log.info("Starting bulk promotion from group {} to {}", request.getSourceGroupId(), request.getTargetGroupId());

        // 1. Validate groups
        Group sourceGroup = groupRepository.findById(request.getSourceGroupId())
            .orElseThrow(() -> new ResourceNotFoundException("Source Group", request.getSourceGroupId()));
        Group targetGroup = groupRepository.findById(request.getTargetGroupId())
            .orElseThrow(() -> new ResourceNotFoundException("Target Group", request.getTargetGroupId()));

        // 2. Validate month range
        if (request.getSourceMonth() < 1 || request.getSourceMonth() > 12 ||
            request.getTargetMonth() < 1 || request.getTargetMonth() > 12) {
            throw new BadRequestException("Month must be between 1 and 12");
        }

        // 3. Find students currently active in source group
        List<StudentGroup> sourceEnrollments = studentGroupRepository.findByGroupIdAndIsActiveTrue(request.getSourceGroupId());
        
        if (sourceEnrollments.isEmpty()) {
            log.info("No active students found in group {}", request.getSourceGroupId());
            Map<String, Object> response = new HashMap<>();
            response.put("count", 0);
            response.put("message", "No active students found in source group");
            return response;
        }

        User promotedBy = getCurrentUser();
        int count = 0;

        for (StudentGroup sg : sourceEnrollments) {
            // Deactivate old enrollment
            sg.setIsActive(false);
            sg.setLeaveDate(LocalDate.now());
            sg.setExitReason("TRANSFERRED");
            sg.setExitNotes("Bulk promoted to group: " + targetGroup.getGroupName());
            studentGroupRepository.save(sg);

            // Create new enrollment in target group
            StudentGroup newEnrollment = StudentGroup.builder()
                .student(sg.getStudent())
                .group(targetGroup)
                .joinDate(LocalDate.of(request.getTargetYear(), request.getTargetMonth(), 1))
                .isActive(true)
                .paymentStatus("PENDING")
                .monthlyPriceOverride(sg.getMonthlyPriceOverride())
                .discountPercentage(sg.getDiscountPercentage())
                .build();
            studentGroupRepository.save(newEnrollment);

            // Record promotion
            Promotion promotion = Promotion.builder()
                .student(sg.getStudent())
                // .fromClass(sourceGroup.getClassEntity()) // Removed as it doesn't exist
                // .toClass(targetGroup.getClassEntity())
                .fromAcademicYear(String.valueOf(request.getSourceYear()))
                .toAcademicYear(String.valueOf(request.getTargetYear()))
                .sourceMonth(request.getSourceMonth())
                .sourceYear(request.getSourceYear())
                .targetMonth(request.getTargetMonth())
                .targetYear(request.getTargetYear())
                .promotionDate(LocalDate.now())
                .promotedBy(promotedBy)
                .remarks(request.getRemarks())
                .build();
            promotionRepository.save(promotion);
            
            count++;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("count", count);
        result.put("message", "Successfully promoted " + count + " students");
        return result;
    }

    private User getCurrentUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username).orElse(null);
    }

    @Transactional
    public void deletePromotion(Long id) {
        promotionRepository.delete(findById(id));
    }

    private Promotion findById(Long id) {
        return promotionRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Promotion", id));
    }

    private PromotionResponse toResponse(Promotion p) {
        return PromotionResponse.builder()
            .id(p.getId())
            .studentId(p.getStudent().getId())
            .studentName(p.getStudent().getFirstName() + " " + p.getStudent().getLastName())
            .fromClassId(p.getFromClass() != null ? p.getFromClass().getId() : null)
            .fromClassName(p.getFromClass() != null ? p.getFromClass().getClassName() : null)
            .toClassId(p.getToClass() != null ? p.getToClass().getId() : null)
            .toClassName(p.getToClass() != null ? p.getToClass().getClassName() : null)
            .fromSectionId(p.getFromSection() != null ? p.getFromSection().getId() : null)
            .fromSectionName(p.getFromSection() != null ? p.getFromSection().getSectionName() : null)
            .toSectionId(p.getToSection() != null ? p.getToSection().getId() : null)
            .toSectionName(p.getToSection() != null ? p.getToSection().getSectionName() : null)
            .fromAcademicYear(p.getFromAcademicYear()).toAcademicYear(p.getToAcademicYear())
            .promotionDate(p.getPromotionDate())
            .promotedByName(p.getPromotedBy() != null ? p.getPromotedBy().getUsername() : null)
            .remarks(p.getRemarks()).createdAt(p.getCreatedAt())
            .build();
    }
}
