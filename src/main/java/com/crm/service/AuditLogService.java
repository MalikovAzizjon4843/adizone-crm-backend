package com.crm.service;

import com.crm.dto.response.AuditLogResponse;
import com.crm.dto.response.PageResponse;
import com.crm.entity.AuditLog;
import com.crm.repository.AuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Audit loglarini o'qish. Yozish {@code com.crm.audit} paketida. */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogService {

    private static final int MAX_PAGE_SIZE = 200;

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> search(
            LocalDate from, LocalDate to,
            Long userId, String action, String entityType, Long entityId,
            String q, int page, int size) {

        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(
            Math.max(page, 0), pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<AuditLog> result = auditLogRepository.findAll(
            buildSpec(from, to, userId, action, entityType, entityId, q), pageable);

        return PageResponse.<AuditLogResponse>builder()
            .content(result.getContent().stream().map(this::toResponse).toList())
            .pageNumber(result.getNumber())
            .pageSize(result.getSize())
            .totalElements(result.getTotalElements())
            .totalPages(result.getTotalPages())
            .last(result.isLast())
            .build();
    }

    @Transactional(readOnly = true)
    public List<AuditLogResponse> getEntityHistory(String entityType, Long entityId) {
        return auditLogRepository
            .findByEntityTypeAndEntityIdOrderByCreatedAtDesc(entityType, entityId)
            .stream()
            .map(this::toResponse)
            .toList();
    }

    /** Dropdown uchun: bazada haqiqatan uchraydigan qiymatlar. */
    @Transactional(readOnly = true)
    public Map<String, Object> getFilterOptions() {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("actions", auditLogRepository.findDistinctActions());
        options.put("entityTypes", auditLogRepository.findDistinctEntityTypes());
        return options;
    }

    private Specification<AuditLog> buildSpec(
            LocalDate from, LocalDate to,
            Long userId, String action, String entityType, Long entityId, String q) {

        final LocalDateTime fromDt = from != null ? from.atStartOfDay() : null;
        final LocalDateTime toDt = to != null ? to.atTime(LocalTime.MAX) : null;
        final String actionValue = blankToNull(action);
        final String entityTypeValue = blankToNull(entityType);
        final String text = blankToNull(q);

        Specification<AuditLog> spec = Specification.where(null);
        if (fromDt != null) {
            spec = spec.and((r, cq, cb) -> cb.greaterThanOrEqualTo(r.get("createdAt"), fromDt));
        }
        if (toDt != null) {
            spec = spec.and((r, cq, cb) -> cb.lessThanOrEqualTo(r.get("createdAt"), toDt));
        }
        if (userId != null) {
            spec = spec.and((r, cq, cb) -> cb.equal(r.get("userId"), userId));
        }
        if (actionValue != null) {
            spec = spec.and((r, cq, cb) -> cb.equal(r.get("action"), actionValue));
        }
        if (entityTypeValue != null) {
            spec = spec.and((r, cq, cb) -> cb.equal(r.get("entityType"), entityTypeValue));
        }
        if (entityId != null) {
            spec = spec.and((r, cq, cb) -> cb.equal(r.get("entityId"), entityId));
        }
        if (text != null) {
            final String like = "%" + text.toLowerCase(Locale.ROOT) + "%";
            spec = spec.and((r, cq, cb) -> cb.or(
                cb.like(cb.lower(cb.coalesce(r.get("summary"), "")), like),
                cb.like(cb.lower(cb.coalesce(r.get("entityLabel"), "")), like),
                cb.like(cb.lower(cb.coalesce(r.get("username"), "")), like)));
        }
        return spec;
    }

    private AuditLogResponse toResponse(AuditLog a) {
        return AuditLogResponse.builder()
            .id(a.getId())
            .createdAt(a.getCreatedAt())
            .userId(a.getUserId())
            .username(a.getUsername())
            .userRole(a.getUserRole())
            .action(a.getAction())
            .entityType(a.getEntityType())
            .entityId(a.getEntityId())
            .entityLabel(a.getEntityLabel())
            .summary(a.getSummary())
            .details(parseDetails(a.getDetailsJson()))
            .ipAddress(a.getIpAddress())
            .build();
    }

    /** Frontend qayta parse qilmasin — JSON obyekt sifatida qaytadi. */
    private Object parseDetails(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            log.warn("Audit detailsJson parse qilinmadi (id bo'yicha xom matn qaytariladi)", e);
            return json;
        }
    }

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }
}
