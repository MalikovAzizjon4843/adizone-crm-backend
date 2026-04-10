package com.crm.service;

import com.crm.dto.request.NoticeRequest;
import com.crm.dto.response.NoticeResponse;
import com.crm.dto.response.PageResponse;
import com.crm.entity.Notice;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.NoticeRepository;
import com.crm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class NoticeService {

    private final NoticeRepository noticeRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public PageResponse<NoticeResponse> getAllNotices(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Notice> p = noticeRepository.findAll(pageable);
        return PageResponse.<NoticeResponse>builder()
            .content(p.getContent().stream().map(this::toResponse).collect(Collectors.toList()))
            .pageNumber(page).pageSize(size)
            .totalElements(p.getTotalElements()).totalPages(p.getTotalPages()).last(p.isLast())
            .build();
    }

    @Transactional(readOnly = true)
    public NoticeResponse getNoticeById(Long id) {
        return toResponse(findById(id));
    }

    @Transactional(readOnly = true)
    public List<NoticeResponse> getLatestNotices(int limit) {
        int n = Math.min(Math.max(limit, 1), 50);
        Pageable pageable = PageRequest.of(0, n);
        return noticeRepository.findByIsActiveTrueAndIsPublishedTrueOrderByPublishedAtDesc(pageable)
            .stream()
            .map(this::toResponse)
            .collect(Collectors.toList());
    }

    @Transactional
    public NoticeResponse createNotice(NoticeRequest request) {
        boolean active = request.getIsActive() != null ? request.getIsActive() : true;
        boolean published = request.getIsPublished() != null ? request.getIsPublished() : true;
        LocalDateTime publishedAt = request.getPublishedAt() != null
            ? request.getPublishedAt()
            : LocalDateTime.now();

        Notice notice = Notice.builder()
            .title(request.getTitle())
            .content(request.getContent())
            .noticeDate(request.getNoticeDate() != null ? request.getNoticeDate() : java.time.LocalDate.now())
            .publishedTo(request.getPublishedTo() != null ? request.getPublishedTo() : "ALL")
            .noticeType(request.getNoticeType() != null ? request.getNoticeType() : "GENERAL")
            .targetRole(request.getTargetRole())
            .isActive(active)
            .isPublished(published && active)
            .publishedAt(publishedAt)
            .expiresAt(request.getExpiresAt())
            .build();

        if (request.getCreatedById() != null) {
            notice.setCreatedBy(userRepository.findById(request.getCreatedById())
                .orElseThrow(() -> new ResourceNotFoundException("User", request.getCreatedById())));
        }

        return toResponse(noticeRepository.save(notice));
    }

    @Transactional
    public NoticeResponse updateNotice(Long id, NoticeRequest request) {
        Notice notice = findById(id);
        notice.setTitle(request.getTitle());
        notice.setContent(request.getContent());
        if (request.getNoticeDate() != null) {
            notice.setNoticeDate(request.getNoticeDate());
        }
        if (request.getPublishedTo() != null) {
            notice.setPublishedTo(request.getPublishedTo());
        }
        if (request.getNoticeType() != null) {
            notice.setNoticeType(request.getNoticeType());
        }
        notice.setTargetRole(request.getTargetRole());
        if (request.getIsActive() != null) {
            notice.setIsActive(request.getIsActive());
        }
        if (request.getIsPublished() != null) {
            notice.setIsPublished(request.getIsPublished());
        }
        if (request.getPublishedAt() != null) {
            notice.setPublishedAt(request.getPublishedAt());
        }
        notice.setExpiresAt(request.getExpiresAt());
        return toResponse(noticeRepository.save(notice));
    }

    @Transactional
    public void deleteNotice(Long id) {
        Notice notice = findById(id);
        notice.setIsActive(false);
        notice.setIsPublished(false);
        noticeRepository.save(notice);
    }

    public Notice findById(Long id) {
        return noticeRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Notice", id));
    }

    private NoticeResponse toResponse(Notice n) {
        return NoticeResponse.builder()
            .id(n.getId()).uuid(n.getUuid())
            .title(n.getTitle()).content(n.getContent())
            .noticeDate(n.getNoticeDate())
            .publishedTo(n.getPublishedTo())
            .noticeType(n.getNoticeType()).targetRole(n.getTargetRole())
            .isActive(n.getIsActive()).isPublished(n.getIsPublished())
            .publishedAt(n.getPublishedAt()).expiresAt(n.getExpiresAt())
            .createdByName(n.getCreatedBy() != null ? n.getCreatedBy().getUsername() : null)
            .createdAt(n.getCreatedAt()).build();
    }
}
