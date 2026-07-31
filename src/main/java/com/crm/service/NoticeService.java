package com.crm.service;

import com.crm.dto.request.NoticeRequest;
import com.crm.dto.response.NoticeResponse;
import com.crm.dto.response.PageResponse;
import com.crm.entity.Notice;
import com.crm.entity.NoticeRead;
import com.crm.entity.User;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.NoticeReadRepository;
import com.crm.repository.NoticeRepository;
import com.crm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class NoticeService {

    private final NoticeRepository noticeRepository;
    private final NoticeReadRepository noticeReadRepository;
    private final UserRepository userRepository;
    private final TeacherAccessService teacherAccessService;

    @Transactional(readOnly = true)
    public PageResponse<NoticeResponse> getAllNotices(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Notice> p = noticeRepository.findAll(pageable);
        Set<Long> readIds = currentUserReadIdsOrEmpty();
        return PageResponse.<NoticeResponse>builder()
            .content(p.getContent().stream()
                .map(n -> toResponse(n, readIds))
                .collect(Collectors.toList()))
            .pageNumber(page).pageSize(size)
            .totalElements(p.getTotalElements()).totalPages(p.getTotalPages()).last(p.isLast())
            .build();
    }

    @Transactional(readOnly = true)
    public NoticeResponse getNoticeById(Long id) {
        Set<Long> readIds = currentUserReadIdsOrEmpty();
        return toResponse(findById(id), readIds);
    }

    /** Bell feed: only active (published + non-expired) notices. */
    @Transactional(readOnly = true)
    public List<NoticeResponse> getLatestNotices(int limit) {
        int n = Math.min(Math.max(limit, 1), 50);
        Pageable pageable = PageRequest.of(0, n);
        Set<Long> readIds = currentUserReadIdsOrEmpty();
        return noticeRepository.findActiveNotices(LocalDate.now().atStartOfDay(), pageable)
            .stream()
            .map(notice -> toResponse(notice, readIds))
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
            .noticeDate(request.getNoticeDate() != null ? request.getNoticeDate() : LocalDate.now())
            .publishedTo(request.getPublishedTo() != null ? request.getPublishedTo() : "ALL")
            .noticeType(request.getNoticeType() != null ? request.getNoticeType() : "GENERAL")
            .targetRole(request.getTargetRole())
            .isActive(active)
            .isPublished(published && active)
            .publishedAt(publishedAt)
            .expiresAt(resolveExpiresAt(request))
            .build();

        if (request.getCreatedById() != null) {
            notice.setCreatedBy(userRepository.findById(request.getCreatedById())
                .orElseThrow(() -> new ResourceNotFoundException("User", request.getCreatedById())));
        } else {
            try {
                notice.setCreatedBy(teacherAccessService.getCurrentUserOrThrow());
            } catch (Exception ignored) {
                // optional on create
            }
        }

        return toResponse(noticeRepository.save(notice), Set.of());
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
        notice.setExpiresAt(resolveExpiresAt(request));
        Set<Long> readIds = currentUserReadIdsOrEmpty();
        return toResponse(noticeRepository.save(notice), readIds);
    }

    @Transactional
    public void deleteNotice(Long id) {
        Notice notice = findById(id);
        notice.setIsActive(false);
        notice.setIsPublished(false);
        noticeRepository.save(notice);
    }

    @Transactional
    public void markRead(Long noticeId) {
        User user = teacherAccessService.getCurrentUserOrThrow();
        findById(noticeId);
        if (!noticeReadRepository.existsByNoticeIdAndUserId(noticeId, user.getId())) {
            NoticeRead read = new NoticeRead();
            read.setNotice(noticeRepository.getReferenceById(noticeId));
            read.setUser(user);
            read.setReadAt(LocalDateTime.now());
            noticeReadRepository.save(read);
        }
    }

    @Transactional
    public void markAllRead() {
        User user = teacherAccessService.getCurrentUserOrThrow();
        Set<Long> alreadyRead = new HashSet<>(noticeReadRepository.findReadNoticeIdsByUser(user.getId()));
        List<Notice> active = noticeRepository.findActiveNotices(LocalDate.now().atStartOfDay());
        for (Notice notice : active) {
            if (alreadyRead.contains(notice.getId())) {
                continue;
            }
            NoticeRead read = new NoticeRead();
            read.setNotice(notice);
            read.setUser(user);
            read.setReadAt(LocalDateTime.now());
            noticeReadRepository.save(read);
        }
    }

    @Transactional(readOnly = true)
    public long getUnreadCount() {
        User user = teacherAccessService.getCurrentUserOrThrow();
        return noticeRepository.countUnreadForUser(user.getId(), LocalDate.now().atStartOfDay());
    }

    public Notice findById(Long id) {
        return noticeRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Notice", id));
    }

    private LocalDateTime resolveExpiresAt(NoticeRequest request) {
        if (request.getExpiresAt() != null) {
            return request.getExpiresAt();
        }
        if (request.getExpiryDate() != null) {
            return request.getExpiryDate().atTime(LocalTime.MAX).withNano(0);
        }
        return null;
    }

    private Set<Long> currentUserReadIdsOrEmpty() {
        try {
            User user = teacherAccessService.getCurrentUserOrThrow();
            return new HashSet<>(noticeReadRepository.findReadNoticeIdsByUser(user.getId()));
        } catch (Exception e) {
            return Set.of();
        }
    }

    private NoticeResponse toResponse(Notice n, Set<Long> readIds) {
        boolean expired = isExpired(n);
        LocalDate expiryDate = n.getExpiresAt() != null ? n.getExpiresAt().toLocalDate() : null;
        return NoticeResponse.builder()
            .id(n.getId()).uuid(n.getUuid())
            .title(n.getTitle()).content(n.getContent())
            .noticeDate(n.getNoticeDate())
            .publishedTo(n.getPublishedTo())
            .noticeType(n.getNoticeType()).targetRole(n.getTargetRole())
            .isActive(n.getIsActive()).isPublished(n.getIsPublished())
            .publishedAt(n.getPublishedAt())
            .expiresAt(n.getExpiresAt())
            .expiryDate(expiryDate)
            .isExpired(expired)
            .isRead(readIds != null && readIds.contains(n.getId()))
            .createdByName(n.getCreatedBy() != null ? n.getCreatedBy().getUsername() : null)
            .createdAt(n.getCreatedAt()).build();
    }

    static boolean isExpired(Notice n) {
        if (n.getExpiresAt() == null) {
            return false;
        }
        return n.getExpiresAt().toLocalDate().isBefore(LocalDate.now());
    }
}
