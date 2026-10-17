package com.crm.notification;

import com.crm.entity.AppIdentity;
import com.crm.entity.User;
import com.crm.entity.UserNotification;
import com.crm.entity.enums.UserRole;
import com.crm.exception.CodedException;
import com.crm.repository.AppIdentityRepository;
import com.crm.repository.AppIdentityStudentRepository;
import com.crm.repository.UserNotificationRepository;
import com.crm.repository.UserRepository;
import com.crm.service.TeacherAccessService;
import com.crm.telegram.TelegramOutboxService;
import com.crm.telegram.TelegramProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
@Slf4j
public class NotificationService {

    static final int MAX_PAGE_SIZE = 100;
    private static final int TITLE_MAX = 255;
    private static final int BODY_MAX = 1000;

    private final UserNotificationRepository repository;
    private final UserRepository userRepository;
    private final TeacherAccessService teacherAccessService;
    private final ApplicationEventPublisher publisher;
    private final Clock billingClock;
    private final NotificationTemplates templates;
    private final AppIdentityRepository identityRepository;
    private final AppIdentityStudentRepository identityStudentRepository;
    private final TelegramOutboxService outboxService;
    private final TelegramProperties telegramProperties;
    private final NotifyProperties notifyProperties;

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

    // ── Yagona kirish nuqtasi (docs/design/notifications.md) ────────────

    /**
     * Hodisani kanallarga tarqatadi — chaqiruvchining tranzaksiyasida (yo'q bo'lsa — o'zinikida):
     * <ul>
     *   <li>xodim → qo'ng'iroqcha ({@link #publish}: yozish va STOMP commit'dan keyin) va, tur
     *       {@link NotificationType#staffTelegram()} bo'lib xodim Telegram'ga ulangan bo'lsa, bot (outbox);</li>
     *   <li>o'quvchi → unga ulangan ota-ona / o'zi ({@code AppIdentityStudent}) — bot (outbox);
     *       ulanmagan — jim o'tkaziladi, {@link DispatchResult#unlinked()} da sanaladi; tur flag bilan o'chiq
     *       bo'lsa ({@link NotifyProperties}) — outbox'ga yozilmaydi, {@link DispatchResult#disabled()} da sanaladi;</li>
     *   <li>aniq chat id lar → bot (outbox).</li>
     * </ul>
     * Rollback bo'lsa hech biri yo'q (outbox — shu tranzaksiyada, qo'ng'iroqcha — commit'dan keyin). Takror —
     * {@code dedupeKey}; sokin soatlar — outbox ({@code TelegramOutboxService}).
     */
    @Transactional
    public DispatchResult dispatch(NotificationRequest request) {
        NotificationType type = request.getType();
        Object[] args = request.argArray();
        DispatchResult result = DispatchResult.EMPTY;

        Set<Long> staff = staffRecipients(request);
        if (!staff.isEmpty()) {
            String variant = request.getVariant();
            publish(new NotificationEvent(type, staff, Set.of(), null, templates.title(type, variant, args),
                templates.body(type, variant, args), request.getLink(), request.getEntityType(),
                request.getEntityId(), request.isCollapse(), request.getDedupeKey()));
            result = result.plus(new DispatchResult(staff.size(), 0, 0, 0));
            if (type.staffTelegram()) {
                String base = request.getStaffTelegramKey() != null ? request.getStaffTelegramKey()
                    : request.getDedupeKey();
                for (Long userId : staff) {
                    AppIdentity identity = identityRepository
                        .findFirstByStaffUserIdAndStatus(userId, AppIdentity.Status.ACTIVE)
                        .filter(i -> i.getChatId() != null)
                        .orElse(null);
                    if (identity != null) {
                        result = result.plus(enqueue(request, identity.getChatId(), identity.getLanguage(), args,
                            key(base, "u" + userId)));
                    }
                }
            }
        }

        if (!request.getStudentIds().isEmpty() && !notifyProperties.studentChannelEnabled(type)) {
            // O'quvchi/ota-ona boti flag bilan o'chiq (NotifyProperties) — outbox'ga yozilmaydi; xodimlarga ta'sir yo'q
            log.info("{} o'chiq (app.notify.*): {} o'quvchiga bot xabari yozilmadi", type,
                request.getStudentIds().size());
            result = result.plus(new DispatchResult(0, 0, 0, 0, request.getStudentIds().size()));
        } else if (!request.getStudentIds().isEmpty()) {
            Map<Long, List<AppIdentity>> linked = new LinkedHashMap<>();
            for (Object[] row : identityStudentRepository.findLinkedIdentities(request.getStudentIds(),
                    request.getRelations(), AppIdentity.Status.ACTIVE)) {
                linked.computeIfAbsent((Long) row[0], k -> new ArrayList<>()).add((AppIdentity) row[1]);
            }
            Set<Long> sentIdentities = new LinkedHashSet<>();
            for (Long studentId : request.getStudentIds()) {
                List<AppIdentity> identities = linked.getOrDefault(studentId, List.of());
                if (identities.isEmpty()) {
                    result = result.plus(new DispatchResult(0, 0, 0, 1));
                    continue;
                }
                for (AppIdentity identity : identities) {
                    // Bir ota-onaga bir hodisa — bitta xabar (farzandlari ko'p bo'lsa ham, kalit identity bo'yicha)
                    if (sentIdentities.add(identity.getId())) {
                        result = result.plus(enqueue(request, identity.getChatId(), identity.getLanguage(), args,
                            key(request.getDedupeKey(), "i" + identity.getId())));
                    }
                }
            }
        }

        for (Long chatId : request.getChatIds()) {
            result = result.plus(enqueue(request, chatId, null, args, key(request.getDedupeKey(), "c" + chatId)));
        }
        return result;
    }

    /** Bot uchun vaqt oynali kalit (chat: 5 daqiqada bitta, sokin soatda tunda bitta) — {@code TelegramOutboxService}. */
    public String windowKey(String prefix) {
        return outboxService.windowKey(prefix);
    }

    /** Hali yuborilmagan bot xabarlarini bekor qilish (kaliti {@code dedupePrefix} bilan boshlanadi). */
    @Transactional
    public int cancelTelegram(String dedupePrefix) {
        return outboxService.cancelPending(dedupePrefix);
    }

    /**
     * Obyekt bo'yicha o'qilmagan bildirishnomalarni o'qilgan qilish (masalan suhbat oxirigacha o'qildi);
     * yangi o'qilmaganlar soni commit'dan keyin STOMP bilan yuboriladi ({@code NOTIFICATIONS_READ}).
     */
    @Transactional
    public int markEntityRead(Long userId, Collection<NotificationType> types, String entityType, Long entityId) {
        int updated = repository.markEntityRead(userId, types.stream().map(Enum::name).toList(), entityType, entityId,
            LocalDateTime.now(billingClock));
        if (updated > 0) {
            publisher.publishEvent(new NotificationReadEvent(userId));
        }
        return updated;
    }

    private Set<Long> staffRecipients(NotificationRequest request) {
        Set<Long> ids = new LinkedHashSet<>(request.getStaffUserIds());
        if (!request.getStaffRoles().isEmpty()) {
            userRepository.findByRoleInAndIsActiveTrueOrderByFirstNameAscLastNameAsc(request.getStaffRoles())
                .forEach(u -> ids.add(u.getId()));
        }
        if (request.getExcludeUserId() != null) {
            ids.remove(request.getExcludeUserId());
        }
        if (ids.isEmpty()) {
            return ids;
        }
        Set<Long> active = new LinkedHashSet<>();
        userRepository.findAllById(ids).stream()
            .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
            .forEach(u -> active.add(u.getId()));
        ids.retainAll(active);
        return ids;
    }

    private DispatchResult enqueue(NotificationRequest request, long chatId, String language, Object[] args,
                                   String dedupeKey) {
        Locale locale = NotificationTemplates.locale(language);
        String text = templates.telegram(request.getType(), request.getVariant(), locale, args);
        if (text == null) {
            return DispatchResult.EMPTY;
        }
        String url = telegramProperties.getWebappUrl();
        Map<String, Object> button = request.getAppPath() == null || url == null || url.isBlank() ? null
            : TelegramOutboxService.openAppButton(templates.text("notify.button.open", locale),
                url + request.getAppPath());
        boolean queued = outboxService.enqueue(chatId, text, button, request.getPriority(), dedupeKey,
            request.getType().name());
        return queued ? new DispatchResult(0, 1, 0, 0) : new DispatchResult(0, 0, 1, 0);
    }

    private static String key(String base, String suffix) {
        return base == null ? null : base + ":" + suffix;
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
            if (event.dedupeKey() != null && repository.existsByUserIdAndDedupeKey(user.getId(), event.dedupeKey())) {
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
                    .dedupeKey(event.dedupeKey())
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

    /** Push uchun: login va o'qilmaganlar soni (bildirishnomasiz); user yo'q — null. */
    @Transactional(readOnly = true)
    public Delivered unreadOf(Long userId) {
        return userRepository.findById(userId)
            .map(u -> new Delivered(u.getUsername(), null, repository.countByUserIdAndReadAtIsNull(userId)))
            .orElse(null);
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
