package com.crm.service;

import com.crm.dto.request.ParentRequest;
import com.crm.dto.response.PageResponse;
import com.crm.dto.response.ParentResponse;
import com.crm.entity.Parent;
import com.crm.entity.Student;
import com.crm.entity.StudentParent;
import com.crm.exception.BadRequestException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.ParentRepository;
import com.crm.repository.StudentParentRepository;
import com.crm.repository.StudentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ParentService {

    private final ParentRepository parentRepository;
    private final StudentParentRepository studentParentRepository;
    private final StudentRepository studentRepository;

    @Transactional(readOnly = true)
    public PageResponse<ParentResponse> getAllParents(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Parent> p = parentRepository.findByIsActiveTrue(pageable);
        return toPageResponse(p, page, size);
    }

    @Transactional(readOnly = true)
    public ParentResponse getParentById(Long id) {
        return toResponse(findById(id), true);
    }

    @Transactional
    public ParentResponse createParent(ParentRequest request) {
        Parent parent = Parent.builder()
            .firstName(request.getFirstName())
            .lastName(request.getLastName())
            .phone(request.getPhone())
            .email(request.getEmail())
            .occupation(request.getOccupation())
            .address(request.getAddress())
            .photoUrl(request.getPhotoUrl())
            .relation(request.getRelation() != null ? request.getRelation() : "PARENT")
            .isActive(true)
            .build();
        return toResponse(parentRepository.save(parent), false);
    }

    @Transactional
    public ParentResponse updateParent(Long id, ParentRequest request) {
        Parent parent = findById(id);
        parent.setFirstName(request.getFirstName());
        parent.setLastName(request.getLastName());
        parent.setPhone(request.getPhone());
        parent.setEmail(request.getEmail());
        parent.setOccupation(request.getOccupation());
        parent.setAddress(request.getAddress());
        parent.setPhotoUrl(request.getPhotoUrl());
        if (request.getRelation() != null) parent.setRelation(request.getRelation());
        return toResponse(parentRepository.save(parent), false);
    }

    @Transactional
    public void deleteParent(Long id) {
        Parent parent = findById(id);
        parent.setIsActive(false);
        parentRepository.save(parent);
    }

    @Transactional(readOnly = true)
    public List<ParentResponse> getParentsByStudent(Long studentId) {
        return studentParentRepository.findByStudentId(studentId).stream()
            .map(sp -> toResponse(sp.getParent(), false))
            .collect(Collectors.toList());
    }

    @Transactional
    public void linkParentToStudent(Long parentId, Map<String, Object> body) {
        Parent parent = findById(parentId);
        Long studentId = Long.valueOf(body.get("studentId").toString());
        Student student = studentRepository.findById(studentId)
            .orElseThrow(() -> new ResourceNotFoundException("Student", studentId));

        if (studentParentRepository.existsByStudentIdAndParentId(studentId, parentId)) {
            throw new BadRequestException("Parent is already linked to this student");
        }

        StudentParent sp = StudentParent.builder()
            .parent(parent)
            .student(student)
            .relation(body.containsKey("relation") ? body.get("relation").toString() : parent.getRelation())
            .isPrimary(body.containsKey("isPrimary") && Boolean.parseBoolean(body.get("isPrimary").toString()))
            .build();
        studentParentRepository.save(sp);
    }

    @Transactional
    public void unlinkParentFromStudent(Long parentId, Long studentId) {
        StudentParent sp = studentParentRepository.findByStudentIdAndParentId(studentId, parentId)
            .orElseThrow(() -> new ResourceNotFoundException("Link between parent and student not found"));
        studentParentRepository.delete(sp);
    }

    public Parent findById(Long id) {
        return parentRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Parent", id));
    }

    // ── Private helpers ────────────────────────────────────────────

    private PageResponse<ParentResponse> toPageResponse(Page<Parent> p, int page, int size) {
        return PageResponse.<ParentResponse>builder()
            .content(p.getContent().stream().map(par -> toResponse(par, false)).collect(Collectors.toList()))
            .pageNumber(page).pageSize(size)
            .totalElements(p.getTotalElements()).totalPages(p.getTotalPages()).last(p.isLast())
            .build();
    }

    private ParentResponse toResponse(Parent p, boolean includeStudents) {
        List<ParentResponse.LinkedStudentResponse> students = null;
        if (includeStudents) {
            students = studentParentRepository.findByParentId(p.getId()).stream()
                .map(sp -> ParentResponse.LinkedStudentResponse.builder()
                    .studentId(sp.getStudent().getId())
                    .firstName(sp.getStudent().getFirstName())
                    .lastName(sp.getStudent().getLastName())
                    .phone(sp.getStudent().getPhone())
                    .relation(sp.getRelation())
                    .isPrimary(sp.getIsPrimary())
                    .build())
                .collect(Collectors.toList());
        }
        return ParentResponse.builder()
            .id(p.getId()).uuid(p.getUuid())
            .firstName(p.getFirstName()).lastName(p.getLastName())
            .phone(p.getPhone()).email(p.getEmail())
            .occupation(p.getOccupation()).address(p.getAddress())
            .photoUrl(p.getPhotoUrl()).relation(p.getRelation())
            .isActive(p.getIsActive()).linkedStudents(students)
            .createdAt(p.getCreatedAt())
            .build();
    }
}
