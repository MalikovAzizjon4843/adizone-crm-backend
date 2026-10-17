package com.crm.notification;

import com.crm.entity.AppIdentityStudent;
import com.crm.entity.TelegramOutbox;
import com.crm.entity.enums.UserRole;
import lombok.Builder;
import lombok.Getter;
import lombok.Singular;

import java.util.List;
import java.util.Set;

/**
 * {@link NotificationService#dispatch} uchun hodisa (docs/design/notifications.md §2). Qabul qiluvchi turiga
 * qarab kanal tanlanadi:
 * <ul>
 *   <li>{@link #staffUserIds} / {@link #staffRoles} — qo'ng'iroqcha ({@code user_notifications} + STOMP) va,
 *       tur {@link NotificationType#staffTelegram()} bo'lsa, Telegram'ga ulangan xodimga bot ham;</li>
 *   <li>{@link #studentIds} — o'quvchiga {@link #relations} bo'yicha ulangan {@code AppIdentity} larga bot
 *       (Telegram outbox);</li>
 *   <li>{@link #chatIds} — aniq chat id lar (direktor xulosasi).</li>
 * </ul>
 * {@link #dedupeKey} — har qabul qiluvchiga qo'shimcha ({@code :u<userId>}, {@code :i<identityId>},
 * {@code :c<chatId>}) bilan; qo'ng'iroqchada kalitning o'zi (V84, {@code UNIQUE (user_id, dedupe_key)}).
 */
@Getter
@Builder
public class NotificationRequest {

    private final NotificationType type;
    /** Shablon varianti: {@code notify.<TUR>.<variant>.*} (masalan {@code bulk}); null — asosiy. */
    private final String variant;

    @Singular
    private final Set<Long> staffUserIds;
    @Singular
    private final Set<UserRole> staffRoles;
    /** Amalni bajargan xodim — o'ziga yuborilmaydi. */
    private final Long excludeUserId;

    @Singular
    private final Set<Long> studentIds;
    @Builder.Default
    private final Set<AppIdentityStudent.Relation> relations =
        Set.of(AppIdentityStudent.Relation.SELF, AppIdentityStudent.Relation.PARENT);

    /**
     * Auditoriya bo'yicha (o'quvchi idsiz): shu munosabatda kamida bitta o'quvchiga ulangan barcha faol identity'lar
     * (masalan e'lon — SELF va/yoki PARENT). Kalit qo'shimchasi — {@code :i<identityId>}.
     */
    @Singular
    private final Set<AppIdentityStudent.Relation> audienceRelations;

    @Singular
    private final List<Long> chatIds;

    /** Shablon argumentlari ({@code {0}}, {@code {1}} …) — tayyor satrlar. */
    @Singular
    private final List<Object> args;

    private final String dedupeKey;
    /**
     * Xodim botiga alohida kalit (masalan chat: {@code TelegramOutboxService.windowKey}); null — {@link #dedupeKey}.
     */
    private final String staffTelegramKey;

    @Builder.Default
    private final TelegramOutbox.Priority priority = TelegramOutbox.Priority.NORMAL;

    /** Qo'ng'iroqcha: frontend yo'li va obyekt. */
    private final String link;
    private final String entityType;
    private final Long entityId;
    /** Qo'ng'iroqcha: shu tur + obyekt bo'yicha o'qilmagani yangilanadi (chat). */
    private final boolean collapse;

    /** Bot tugmasi: Mini App ichidagi yo'l (masalan {@code /#/payments}); null — tugmasiz. */
    private final String appPath;

    Object[] argArray() {
        return args == null ? new Object[0] : args.toArray();
    }
}
