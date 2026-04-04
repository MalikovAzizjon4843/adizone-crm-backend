package com.crm.service;

import com.crm.dto.request.LeadRequest;
import com.crm.dto.response.LeadResponse;
import com.crm.dto.response.PageResponse;
import com.crm.entity.Lead;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.LeadRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LeadService {

    private final LeadRepository leadRepository;

    @Transactional
    public LeadResponse createLead(LeadRequest request) {
        Lead lead = Lead.builder()
                .fullName(request.getFullName())
                .phone(request.getPhone())
                .address(request.getAddress())
                .course(request.getCourse())
                .format(request.getFormat() != null
                        ? request.getFormat().toUpperCase()
                        : "OFFLINE")
                .source(request.getSource() != null
                        ? request.getSource().toUpperCase()
                        : "WEBSITE")
                .notes(request.getNotes())
                .status("NEW")
                .converted(false)
                .build();
        return toResponse(leadRepository.save(lead));
    }

    @Transactional(readOnly = true)
    public PageResponse<LeadResponse> getAll(int page, int size, String status, String search) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Lead> leads;
        if (search != null && !search.isBlank()) {
            leads = leadRepository.search(search.trim(), pageable);
        } else if (status != null && !status.isBlank()) {
            leads = leadRepository.findByStatusOrderByCreatedAtDesc(status.toUpperCase(), pageable);
        } else {
            leads = leadRepository.findAllByOrderByCreatedAtDesc(pageable);
        }
        return PageResponse.<LeadResponse>builder()
                .content(leads.getContent().stream()
                        .map(this::toResponse)
                        .collect(Collectors.toList()))
                .pageNumber(page)
                .pageSize(size)
                .totalElements(leads.getTotalElements())
                .totalPages(leads.getTotalPages())
                .last(leads.isLast())
                .build();
    }

    @Transactional(readOnly = true)
    public LeadResponse getById(Long id) {
        return toResponse(leadRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Lead", id)));
    }

    @Transactional
    public LeadResponse updateStatus(Long id, String status, String notes) {
        Lead lead = leadRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Lead", id));
        lead.setStatus(status.toUpperCase());
        if (notes != null) {
            lead.setNotes(notes);
        }
        return toResponse(leadRepository.save(lead));
    }

    @Transactional
    public LeadResponse convertToStudent(Long id) {
        Lead lead = leadRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Lead", id));
        lead.setConverted(true);
        lead.setStatus("ENROLLED");
        return toResponse(leadRepository.save(lead));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", leadRepository.count());
        stats.put("new", leadRepository.countByStatus("NEW"));
        stats.put("contacted", leadRepository.countByStatus("CONTACTED"));
        stats.put("interested", leadRepository.countByStatus("INTERESTED"));
        stats.put("enrolled", leadRepository.countByStatus("ENROLLED"));
        stats.put("rejected", leadRepository.countByStatus("REJECTED"));

        Map<String, Long> byStatus = new LinkedHashMap<>();
        leadRepository.countByStatusGrouped()
                .forEach(row -> byStatus.put(
                        row[0] != null ? row[0].toString() : "UNKNOWN",
                        (Long) row[1]));
        stats.put("byStatus", byStatus);

        Map<String, Long> bySource = new LinkedHashMap<>();
        leadRepository.countBySourceGrouped()
                .forEach(row -> bySource.put(
                        row[0] != null ? row[0].toString() : "UNKNOWN",
                        (Long) row[1]));
        stats.put("bySource", bySource);
        return stats;
    }

    private LeadResponse toResponse(Lead l) {
        String studentName = null;
        if (l.getStudent() != null) {
            studentName = (l.getStudent().getFirstName() != null ? l.getStudent().getFirstName() : "")
                    + " "
                    + (l.getStudent().getLastName() != null ? l.getStudent().getLastName() : "");
            studentName = studentName.trim();
        }
        return LeadResponse.builder()
                .id(l.getId())
                .uuid(l.getUuid())
                .fullName(l.getFullName())
                .phone(l.getPhone())
                .address(l.getAddress())
                .course(l.getCourse())
                .format(l.getFormat())
                .status(l.getStatus())
                .source(l.getSource())
                .notes(l.getNotes())
                .converted(l.getConverted())
                .studentId(l.getStudent() != null ? l.getStudent().getId() : null)
                .studentName(studentName != null && !studentName.isEmpty() ? studentName : null)
                .createdAt(l.getCreatedAt())
                .updatedAt(l.getUpdatedAt())
                .build();
    }
}
