package com.crm.miniapp;

import com.crm.entity.AppIdentity;
import com.crm.entity.AppIdentityStudent;
import com.crm.entity.AppNoticeRead;
import com.crm.entity.AppNotificationPref;
import com.crm.entity.Notice;
import com.crm.entity.enums.UserRole;
import com.crm.exception.CodedException;
import com.crm.notification.AppNotifyTopic;
import com.crm.notification.NotifyProperties;
import com.crm.repository.AppIdentityStudentRepository;
import com.crm.repository.AppNoticeReadRepository;
import com.crm.repository.AppNotificationPrefRepository;
import com.crm.repository.NoticeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mini App: e'lonlar ({@code GET /api/app/notices}, {@code POST /api/app/notices/{id}/read}) va bildirishnoma
 * sozlamalari ({@code GET/PUT /api/app/notification-settings}) — docs/design/notifications.md §6–§7.
 *
 * <p>E'lon auditoriyasi identity'ning munosabatlaridan: SELF → {@code STUDENT}, PARENT → {@code PARENT}. Faqat
 * AYNAN shu rolga qaratilgan, faol, chop etilgan, vaqti kelgan, muddati o'tmagan e'lonlar
 * ({@code NoticeRepository.findAppVisible}); "hamma" (bo'sh auditoriya) e'lon — xodimlar uchun, bu yerda yo'q.
 */
@Service
@RequiredArgsConstructor
public class MiniAppNoticeService {

    private final MiniAppAuthService authService;
    private final AppIdentityStudentRepository identityStudentRepository;
    private final NoticeRepository noticeRepository;
    private final AppNoticeReadRepository readRepository;
    private final AppNotificationPrefRepository prefRepository;
    private final NotifyProperties notifyProperties;
    private final Clock billingClock;

    // ── E'lonlar ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public AppDtos.Notices list(AppPrincipal principal) {
        AppIdentity identity = authService.identity(principal);
        List<Notice> notices = visible(identity.getId());
        Set<Long> read = notices.isEmpty() ? Set.of()
            : new HashSet<>(readRepository.findReadNoticeIds(identity.getId(), notices.stream().map(Notice::getId).toList()));
        List<AppDtos.NoticeItem> items = notices.stream()
            .map(n -> new AppDtos.NoticeItem(n.getId(), n.getTitle(), n.getContent(), n.getNoticeType(),
                n.getPublishedAt() != null ? n.getPublishedAt() : n.getCreatedAt(), n.getExpiresAt(),
                read.contains(n.getId())))
            .toList();
        return new AppDtos.Notices(items, items.stream().filter(i -> !i.read()).count());
    }

    /** O'qildi (idempotent); ko'rinmaydigan yoki yo'q e'lon — 404 {@code app.notice.notFound}. */
    @Transactional
    public AppDtos.Notices markRead(AppPrincipal principal, Long noticeId) {
        AppIdentity identity = authService.identity(principal);
        boolean visible = visible(identity.getId()).stream().anyMatch(n -> n.getId().equals(noticeId));
        if (!visible) {
            throw CodedException.notFound("app.notice.notFound");
        }
        if (!readRepository.existsByNoticeIdAndIdentityId(noticeId, identity.getId())) {
            readRepository.save(AppNoticeRead.builder()
                .noticeId(noticeId).identityId(identity.getId()).readAt(LocalDateTime.now(billingClock)).build());
        }
        return list(principal);
    }

    private List<Notice> visible(Long identityId) {
        Set<UserRole> roles = EnumSet.noneOf(UserRole.class);
        for (AppIdentityStudent.Relation r : identityStudentRepository.findRelations(identityId)) {
            roles.add(r == AppIdentityStudent.Relation.SELF ? UserRole.STUDENT : UserRole.PARENT);
        }
        // NoticeService bilan bir xil soat: publishedAt / expiresAt LocalDateTime.now() bilan yoziladi va tekshiriladi
        LocalDateTime now = LocalDateTime.now();
        Map<Long, Notice> byId = new LinkedHashMap<>();
        for (UserRole role : roles) {
            String legacy = role == UserRole.STUDENT ? "STUDENTS" : "PARENTS";
            noticeRepository.findAppVisible(now.toLocalDate().atStartOfDay(), now, role, role.name(), legacy)
                .forEach(n -> byId.putIfAbsent(n.getId(), n));
        }
        List<Notice> out = new ArrayList<>(byId.values());
        out.sort(Comparator.comparing((Notice n) -> n.getPublishedAt() != null ? n.getPublishedAt() : n.getCreatedAt(),
            Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        return out;
    }

    // ── Bildirishnoma sozlamalari ────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<AppDtos.NotifySetting> settings(AppPrincipal principal) {
        return settings(authService.identity(principal).getId());
    }

    /**
     * Qisman yangilash: {@code {"lesson": false, "notice": true}} — berilmaganlari o'zgarmaydi. Noma'lum mavzu —
     * 400 {@code app.notifySettings.unknownTopic}, qiymat null — 400 {@code app.notifySettings.valueRequired}.
     */
    @Transactional
    public List<AppDtos.NotifySetting> update(AppPrincipal principal, Map<String, Boolean> changes) {
        Long identityId = authService.identity(principal).getId();
        Map<AppNotifyTopic, Boolean> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, Boolean> e : (changes == null ? Map.<String, Boolean>of() : changes).entrySet()) {
            AppNotifyTopic topic = AppNotifyTopic.ofCode(e.getKey())
                .orElseThrow(() -> CodedException.badRequest("app.notifySettings.unknownTopic", e.getKey()));
            if (e.getValue() == null) {
                throw CodedException.badRequest("app.notifySettings.valueRequired", e.getKey());
            }
            parsed.put(topic, e.getValue());
        }
        LocalDateTime now = LocalDateTime.now(billingClock);
        parsed.forEach((topic, enabled) -> {
            AppNotificationPref pref = prefRepository.findByIdentityIdAndTopic(identityId, topic.code())
                .orElseGet(() -> AppNotificationPref.builder().identityId(identityId).topic(topic.code()).build());
            pref.setEnabled(enabled);
            pref.setUpdatedAt(now);
            prefRepository.save(pref);
        });
        return settings(identityId);
    }

    private List<AppDtos.NotifySetting> settings(Long identityId) {
        Map<String, Boolean> stored = new LinkedHashMap<>();
        prefRepository.findByIdentityId(identityId).forEach(p -> stored.put(p.getTopic(), p.getEnabled()));
        List<AppDtos.NotifySetting> out = new ArrayList<>();
        for (AppNotifyTopic topic : AppNotifyTopic.values()) {
            out.add(new AppDtos.NotifySetting(topic.code(), stored.getOrDefault(topic.code(), true),
                notifyProperties.studentChannelEnabled(topic.type())));
        }
        return out;
    }
}
