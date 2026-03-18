package com.crm.service;

import com.crm.dto.request.PromotionRequest;
import com.crm.dto.response.PageResponse;
import com.crm.dto.response.PromotionResponse;
import com.crm.entity.*;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PromotionService {

    private final PromotionRepository promotionRepository;
    private final StudentRepository studentRepository;
    private final ClassRepository classRepository;
    private final SectionRepository sectionRepository;
    private final UserRepository userRepository;

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
