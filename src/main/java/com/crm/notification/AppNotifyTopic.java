package com.crm.notification;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Mini App foydalanuvchisi o'zi yoqib/o'chiradigan bot xabari mavzulari ({@code GET/PUT /api/app/notification-settings},
 * {@code app_notification_prefs}, V85). Qator yo'q — yoqiq. {@link NotificationService#dispatch} o'quvchi kanalida
 * hurmat qiladi.
 */
public enum AppNotifyTopic {
    ABSENT("absent", NotificationType.STUDENT_ABSENT),
    PAYMENT_OVERDUE("payment_overdue", NotificationType.PAYMENT_OVERDUE),
    PAYMENT_DUE_SOON("payment_due_soon", NotificationType.PAYMENT_DUE_SOON),
    LESSON("lesson", NotificationType.LESSON_REMINDER),
    NOTICE("notice", NotificationType.NOTICE_APP);

    private final String code;
    private final NotificationType type;

    AppNotifyTopic(String code, NotificationType type) {
        this.code = code;
        this.type = type;
    }

    /** API va jadvaldagi nom: {@code absent}, {@code payment_overdue}, … */
    public String code() {
        return code;
    }

    public NotificationType type() {
        return type;
    }

    public static Optional<AppNotifyTopic> of(NotificationType type) {
        return Arrays.stream(values()).filter(t -> t.type == type).findFirst();
    }

    public static Optional<AppNotifyTopic> ofCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String c = code.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(t -> t.code.equals(c)).findFirst();
    }
}
