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

import java.util.Comparator;
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
            .fullName(request.getFullName())
            .phone(request.getPhone())
            .address(request.getAddress())
            .relation(request.getRelation() != null ? request.getRelation() : "OTHER")
            .isActive(true)
            .build();
        parent = parentRepository.save(parent);

        if (request.getStudentId() != null) {
            linkParentToStudentInternal(parent, request.getStudentId(),
                request.getRelation() != null ? request.getRelation() : parent.getRelation(), true);
        }

        return toResponse(parent, false);
    }

    @Transactional
    public ParentResponse updateParent(Long id, ParentRequest request) {
        Parent parent = findById(id);
        parent.setFullName(request.getFullName());
        parent.setPhone(request.getPhone());
        parent.setAddress(request.getAddress());
        if (request.getRelation() != null) {
            parent.setRelation(request.getRelation());
        }
        parent = parentRepository.save(parent);

        if (request.getStudentId() != null) {
            if (!studentParentRepository.existsByStudentIdAndParentId(request.getStudentId(), parent.getId())) {
                linkParentToStudentInternal(parent, request.getStudentId(),
                    request.getRelation() != null ? request.getRelation() : parent.getRelation(), false);
            }
        }

        return toResponse(parent, false);
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
        String relation = body.containsKey("relation") ? body.get("relation").toString() : parent.getRelation();
        boolean isPrimary = body.containsKey("isPrimary") && Boolean.parseBoolean(body.get("isPrimary").toString());

        if (studentParentRepository.existsByStudentIdAndParentId(studentId, parentId)) {
            throw new BadRequestException("Parent is already linked to this student");
        }

        linkParentToStudentInternal(parent, studentId, relation, isPrimary);
    }

    private void linkParentToStudentInternal(Parent parent, Long studentId, String relation, boolean isPrimary) {
        Student student = studentRepository.findById(studentId)
            .orElseThrow(() -> new ResourceNotFoundException("Student", studentId));

        StudentParent sp = StudentParent.builder()
            .parent(parent)
            .student(student)
            .relation(relation)
            .isPrimary(isPrimary)
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

    private PageResponse<ParentResponse> toPageResponse(Page<Parent> p, int page, int size) {
        return PageResponse.<ParentResponse>builder()
            .content(p.getContent().stream().map(par -> toResponse(par, false)).collect(Collectors.toList()))
            .pageNumber(page).pageSize(size)
            .totalElements(p.getTotalElements()).totalPages(p.getTotalPages()).last(p.isLast())
            .build();
    }

    private ParentResponse toResponse(Parent p, boolean includeStudents) {
        List<ParentResponse.LinkedStudentResponse> linkedStudents = null;
        Long studentId = null;
        String studentName = null;

        if (includeStudents) {
            linkedStudents = studentParentRepository.findByParentId(p.getId()).stream()
                .sorted(Comparator.comparing(StudentParent::getIsPrimary, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(sp -> ParentResponse.LinkedStudentResponse.builder()
                    .studentId(sp.getStudent().getId())
                    .firstName(sp.getStudent().getFirstName())
                    .lastName(sp.getStudent().getLastName())
                    .phone(sp.getStudent().getPhone())
                    .relation(sp.getRelation())
                    .isPrimary(sp.getIsPrimary())
                    .build())
                .collect(Collectors.toList());
            if (!linkedStudents.isEmpty()) {
                ParentResponse.LinkedStudentResponse first = linkedStudents.get(0);
                studentId = first.getStudentId();
                studentName = first.getFirstName() + " " + first.getLastName();
            }
        }

        return ParentResponse.builder()
            .id(p.getId())
            .fullName(p.getFullName())
            .phone(p.getPhone())
            .address(p.getAddress())
            .relation(p.getRelation())
            .studentId(studentId)
            .studentName(studentName)
            .isActive(p.getIsActive())
            .linkedStudents(linkedStudents)
            .createdAt(p.getCreatedAt())
            .build();
    }
}
