package com.crm.notification;

import com.crm.entity.User;
import com.crm.entity.UserNotification;
import com.crm.entity.enums.UserRole;
import com.crm.exception.CodedException;
import com.crm.repository.UserNotificationRepository;
import com.crm.repository.UserRepository;
import com.crm.service.TeacherAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Xodim bildirishnomalari (CRM qo'ng'iroqchasi).
 * <ul>
 *   <li>{@link #publish} — biznes servislari chaqiradi (o'z tranzaksiyasida); yozish va push commit'dan keyin;</li>
 *   <li>{@link #store} — {@link UserNotificationListener} chaqiradi, alohida tranzaksiyada;</li>
 *   <li>o'qish / belgilash — faqat joriy foydalanuvchining yozuvlari (begonasi — 404).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    static final int MAX_PAGE_SIZE = 100;
    private static final int TITLE_MAX = 255;
    private static final int BODY_MAX = 1000;

    private final UserNotificationRepository repository;
    private final UserRepository userRepository;
    private final TeacherAccessService teacherAccessService;
    private final ApplicationEventPublisher publisher;
    private final Clock billingClock;

    /** Yozilgan bildirishnoma va qabul qiluvchining push uchun ma'lumotlari. */
    public record Delivered(String username, UserNotificationResponse notification, long unreadCount) {
    }

    // ── E'lon qilish ─────────────────────────────────────────────────────

    public void publish(NotificationEvent event) {
        publisher.publishEvent(event);
    }

    public void toUsers(NotificationType type, Collection<Long> userIds, Long excludeUserId, String title, String body,
                        String link, String entityType, Long entityId) {
        publish(new NotificationEvent(type, new LinkedHashSet<>(userIds), Set.of(), excludeUserId, title, body, link,
            entityType, entityId, false));
    }

    public void toRoles(NotificationType type, Set<UserRole> roles, Long excludeUserId, String title, String body,
                        String link, String entityType, Long entityId) {
        publish(new NotificationEvent(type, Set.of(), roles, excludeUserId, title, body, link, entityType, entityId,
            false));
    }

    /** Joriy xodim id si (amalni bajargan — o'ziga yuborilmasin); kontekstsiz chaqiruvda null. */
    public Long currentUserIdOrNull() {
        try {
            return teacherAccessService.getCurrentUserOrThrow().getId();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ── Yozish (commit'dan keyin, alohida tranzaksiya) ───────────────────

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Delivered> store(NotificationEvent event) {
        Set<Long> ids = new LinkedHashSet<>(event.userIds());
        if (!event.roles().isEmpty()) {
            userRepository.findByRoleInAndIsActiveTrueOrderByFirstNameAscLastNameAsc(event.roles())
                .forEach(u -> ids.add(u.getId()));
        }
        if (event.excludeUserId() != null) {
            ids.remove(event.excludeUserId());
        }
        LocalDateTime now = LocalDateTime.now(billingClock);
        List<Delivered> out = new ArrayList<>();
        for (User user : userRepository.findAllById(ids)) {
            if (!Boolean.TRUE.equals(user.getIsActive())) {
                continue;
            }
            UserNotification n = event.collapse() && event.entityId() != null
                ? repository.findFirstByUserIdAndTypeAndEntityTypeAndEntityIdAndReadAtIsNullOrderByIdDesc(
                    user.getId(), event.type().name(), event.entityType(), event.entityId()).orElse(null)
                : null;
            if (n == null) {
                n = UserNotification.builder()
                    .userId(user.getId())
                    .type(event.type().name())
                    .entityType(event.entityType())
                    .entityId(event.entityId())
                    .build();
            }
            n.setTitle(cut(event.title(), TITLE_MAX));
            n.setBody(cut(event.body(), BODY_MAX));
            n.setLink(cut(event.link(), 500));
            n.setCreatedAt(now);
            n = repository.save(n);
            repository.flush();
            out.add(new Delivered(user.getUsername(), UserNotificationResponse.of(n),
                repository.countByUserIdAndReadAtIsNull(user.getId())));
        }
        return out;
    }

    // ── O'qish / belgilash (joriy foydalanuvchi) ─────────────────────────

    @Transactional(readOnly = true)
    public Page<UserNotificationResponse> mine(int page, int size) {
        Long userId = teacherAccessService.getCurrentUserOrThrow().getId();
        int pageSize = Math.max(1, Math.min(MAX_PAGE_SIZE, size));
        return repository.findByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(Math.max(0, page), pageSize))
            .map(UserNotificationResponse::of);
    }

    @Transactional(readOnly = true)
    public long unreadCount() {
        return repository.countByUserIdAndReadAtIsNull(teacherAccessService.getCurrentUserOrThrow().getId());
    }

    /** O'zinikini o'qildi deb belgilash (idempotent); begona yoki yo'q — 404 {@code notification.notFound}. */
    @Transactional
    public UserNotificationResponse markRead(Long id) {
        Long userId = teacherAccessService.getCurrentUserOrThrow().getId();
        UserNotification n = repository.findByIdAndUserId(id, userId)
            .orElseThrow(() -> CodedException.notFound("notification.notFound"));
        if (n.getReadAt() == null) {
            n.setReadAt(LocalDateTime.now(billingClock));
        }
        return UserNotificationResponse.of(n);
    }

    @Transactional
    public int markAllRead() {
        return repository.markAllRead(teacherAccessService.getCurrentUserOrThrow().getId(),
            LocalDateTime.now(billingClock));
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max - 3) + "..." : s;
    }
}
