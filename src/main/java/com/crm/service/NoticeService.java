package com.crm.service;

import com.crm.audit.AuditAction;
import com.crm.audit.Audited;
import com.crm.config.Messages;
import com.crm.dto.request.NoticeRequest;
import com.crm.dto.response.NoticeResponse;
import com.crm.dto.response.PageResponse;
import com.crm.entity.Notice;
import com.crm.entity.NoticeRead;
import com.crm.entity.User;
import com.crm.entity.enums.UserRole;
import com.crm.exception.CodedException;
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
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class NoticeService {

    private final NoticeRepository noticeRepository;
    private final NoticeReadRepository noticeReadRepository;
    private final Messages messages;
    private final UserRepository userRepository;
    private final TeacherAccessService teacherAccessService;

    /**
     * SA/ADMIN — boshqaruv ro'yxati: hamma e'lonlar (qoralama, nofaol, muddati o'tgan).
     * Boshqa xodim — faqat o'z roliga ko'rinadigan faol e'lonlar (N-01; avval hammaga hammasi).
     */
    @Transactional(readOnly = true)
    public PageResponse<NoticeResponse> getAllNotices(int page, int size) {
        User user = teacherAccessService.getCurrentUserOrThrow();
        int pageSize = Math.min(Math.max(size, 1), 100);
        Page<Notice> p;
        if (isManager(user)) {
            p = noticeRepository.findAll(PageRequest.of(page, pageSize, Sort.by(Sort.Direction.DESC, "createdAt")));
        } else {
            Audience a = Audience.of(user.getRole());
            p = noticeRepository.pageVisibleActive(LocalDate.now().atStartOfDay(), a.role(), a.roleName(),
                a.legacyAudience(), PageRequest.of(page, pageSize));
        }
        Set<Long> readIds = currentUserReadIdsOrEmpty();
        return PageResponse.<NoticeResponse>builder()
            .content(p.getContent().stream()
                .map(n -> toResponse(n, readIds))
                .collect(Collectors.toList()))
            .pageNumber(page).pageSize(pageSize)
            .totalElements(p.getTotalElements()).totalPages(p.getTotalPages()).last(p.isLast())
            .build();
    }

    @Transactional(readOnly = true)
    public NoticeResponse getNoticeById(Long id) {
        Notice notice = findVisible(id);
        Set<Long> readIds = currentUserReadIdsOrEmpty();
        return toResponse(notice, readIds);
    }

    /** Bell feed: only active (published + non-expired) notices. */
    @Transactional(readOnly = true)
    public List<NoticeResponse> getLatestNotices(int limit) {
        int n = Math.min(Math.max(limit, 1), 50);
        Pageable pageable = PageRequest.of(0, n);
        Set<Long> readIds = currentUserReadIdsOrEmpty();
        Audience a = Audience.of(teacherAccessService.getCurrentUserOrThrow().getRole());
        return noticeRepository.findVisibleActive(LocalDate.now().atStartOfDay(), a.role(), a.roleName(),
                a.legacyAudience(), pageable)
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
            .noticeType(request.getNoticeType() != null ? request.getNoticeType() : "GENERAL")
            .isActive(active)
            .isPublished(published && active)
            .publishedAt(publishedAt)
            .expiresAt(resolveExpiresAt(request))
            .build();
        Set<UserRole> roles = requestedAudience(request);
        applyAudience(notice, roles != null ? roles : Set.of());

        if (request.getCreatedById() != null) {
            notice.setCreatedBy(userRepository.findById(request.getCreatedById())
                .orElseThrow(() -> new ResourceNotFoundException("User", request.getCreatedById())));
        } else {
            // Endpoint SUPER_ADMIN/ADMIN bilan himoyalangan, ya'ni autentifikatsiya
            // qilingan foydalanuvchi doim bor. Ilgari bu chaqiruv try/catch ichida
            // edi va xato yutilib, createdBy NULL bo'lib qolardi.
            notice.setCreatedBy(teacherAccessService.getCurrentUserOrThrow());
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
        // Auditoriya faqat yuborilganda o'zgaradi (avval targetRole har tahrirda NULL bo'lardi — N-06).
        Set<UserRole> roles = requestedAudience(request);
        if (roles != null) {
            applyAudience(notice, roles);
        }
        if (request.getNoticeType() != null) {
            notice.setNoticeType(request.getNoticeType());
        }
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

    /**
     * Jismoniy o'chirish. E'lon vaqtinchalik ma'lumot — tarixi saqlanmaydi.
     *
     * <p>Avval notice_reads tozalanadi: uning notice_id ustuni NOT NULL FK,
     * shuning uchun o'qilganlik yozuvi bor e'lonni to'g'ridan-to'g'ri o'chirib
     * bo'lmaydi. Bitta bulk DELETE — yozuvlar entity sifatida yuklanmaydi.
     */
    @Transactional
    @Audited(action = AuditAction.DELETE, entity = "Notice", entityId = "#id")
    public void deleteNotice(Long id) {
        Notice notice = findById(id);
        noticeReadRepository.deleteByNoticeId(id);
        noticeRepository.delete(notice);
    }

    @Transactional
    public void markRead(Long noticeId) {
        User user = teacherAccessService.getCurrentUserOrThrow();
        findVisible(noticeId);
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
        Audience a = Audience.of(user.getRole());
        List<Notice> active = noticeRepository.findVisibleActive(LocalDate.now().atStartOfDay(), a.role(),
            a.roleName(), a.legacyAudience(), Pageable.unpaged());
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
        Audience a = Audience.of(user.getRole());
        return noticeRepository.countUnreadVisible(user.getId(), LocalDate.now().atStartOfDay(),
            a.role(), a.roleName(), a.legacyAudience());
    }

    public Notice findById(Long id) {
        return noticeRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(
                messages.get("error.notice.notFound", id)));
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
            .targetRoles(effectiveAudience(n).stream().map(Enum::name).sorted().toList())
            .audienceAll(effectiveAudience(n).isEmpty())
            .isActive(n.getIsActive()).isPublished(n.getIsPublished())
            .publishedAt(n.getPublishedAt())
            .expiresAt(n.getExpiresAt())
            .expiryDate(expiryDate)
            .isExpired(expired)
            .isRead(readIds != null && readIds.contains(n.getId()))
            .createdByName(n.getCreatedBy() != null ? n.getCreatedBy().getUsername() : null)
            .createdAt(n.getCreatedAt()).build();
    }

    // ── Auditoriya (phase5-audit N-01, Q12) ─────────────────────────────

    /** Eski {@code publishedTo} qiymati → rol. {@code ALL} — cheklovsiz. */
    private static final Map<String, UserRole> LEGACY_AUDIENCE = Map.of(
        "TEACHERS", UserRole.TEACHER,
        "STUDENTS", UserRole.STUDENT,
        "PARENTS", UserRole.PARENT);

    /** Repozitoriy parametrlari: rol, uning nomi va eski {@code publishedTo} dagi nomi (bo'lmasa "-"). */
    record Audience(UserRole role, String roleName, String legacyAudience) {
        static Audience of(UserRole role) {
            String legacy = LEGACY_AUDIENCE.entrySet().stream()
                .filter(e -> e.getValue() == role)
                .map(Map.Entry::getKey)
                .findFirst().orElse("-");
            return new Audience(role, role.name(), legacy);
        }
    }

    /** E'lonlarni boshqaradiganlar — hammasini ko'radi. */
    private static boolean isManager(User user) {
        return user.getRole() == UserRole.SUPER_ADMIN || user.getRole() == UserRole.ADMIN;
    }

    /** SA/ADMIN — istalgan e'lon; boshqalar — faqat o'ziga ko'rinadigan faol e'lon, aks holda 404. */
    private Notice findVisible(Long id) {
        Notice notice = findById(id);
        User user = teacherAccessService.getCurrentUserOrThrow();
        if (isManager(user)) {
            return notice;
        }
        Audience a = Audience.of(user.getRole());
        if (!noticeRepository.isVisibleActive(id, LocalDate.now().atStartOfDay(),
                a.role(), a.roleName(), a.legacyAudience())) {
            throw new ResourceNotFoundException(messages.get("error.notice.notFound", id));
        }
        return notice;
    }

    /**
     * So'rovdagi auditoriya: {@code targetRoles} → bo'lmasa eski {@code targetRole} → bo'lmasa eski
     * {@code publishedTo}. Hech biri yo'q — {@code null} (PUT da o'zgarmaydi). Bo'sh to'plam — hamma.
     */
    private Set<UserRole> requestedAudience(NoticeRequest request) {
        if (request.getTargetRoles() != null) {
            Set<UserRole> roles = EnumSet.noneOf(UserRole.class);
            request.getTargetRoles().stream().filter(java.util.Objects::nonNull).forEach(roles::add);
            return roles;
        }
        if (request.getTargetRole() != null && !request.getTargetRole().isBlank()) {
            return EnumSet.of(parseRole(request.getTargetRole()));
        }
        if (request.getPublishedTo() != null && !request.getPublishedTo().isBlank()) {
            return legacyRoles(request.getPublishedTo(), true);
        }
        return null;
    }

    /** Yangi model: rollar jadvalda; eski maydonlar faqat ko'rsatish uchun izchil qiymatda. */
    private static void applyAudience(Notice notice, Set<UserRole> roles) {
        notice.getTargetRoles().clear();
        notice.getTargetRoles().addAll(roles);
        notice.setPublishedTo(roles.isEmpty() ? "ALL" : "ROLES");
        notice.setTargetRole(null);
    }

    /** Javobdagi auditoriya: jadval bo'sh bo'lsa — eski maydonlardan (V60 bajarilmagan yozuvlar). */
    private static Set<UserRole> effectiveAudience(Notice n) {
        if (!n.getTargetRoles().isEmpty()) {
            return n.getTargetRoles();
        }
        if (n.getTargetRole() != null && !n.getTargetRole().isBlank()) {
            try {
                return EnumSet.of(UserRole.valueOf(n.getTargetRole().trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                return Set.of();
            }
        }
        return n.getPublishedTo() != null ? legacyRoles(n.getPublishedTo(), false) : Set.of();
    }

    private static Set<UserRole> legacyRoles(String publishedTo, boolean strict) {
        String key = publishedTo.trim().toUpperCase(Locale.ROOT);
        if (key.equals("ALL") || key.equals("ROLES")) {
            return EnumSet.noneOf(UserRole.class);
        }
        UserRole role = LEGACY_AUDIENCE.get(key);
        if (role == null) {
            if (strict) {
                throw CodedException.badRequest("notice.audience.invalid", publishedTo);
            }
            return Set.of();
        }
        return EnumSet.of(role);
    }

    private static UserRole parseRole(String raw) {
        try {
            return UserRole.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw CodedException.badRequest("notice.audience.invalid", raw);
        }
    }

    static boolean isExpired(Notice n) {
        if (n.getExpiresAt() == null) {
            return false;
        }
        return n.getExpiresAt().toLocalDate().isBefore(LocalDate.now());
    }
}
