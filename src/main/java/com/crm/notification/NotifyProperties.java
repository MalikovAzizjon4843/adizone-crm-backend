package com.crm.notification;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * O'quvchi / ota-onaga ketadigan bot xabarlari flag'lari (docs/design/notifications.md §4.1). Standart O'CHIQ:
 * prodda shablon va qabul qiluvchilar tekshirilgandan keyin env bilan yoqiladi. Xodimlarning qo'ng'iroqchasiga
 * ta'sir qilmaydi — faqat {@link NotificationService#dispatch} dagi o'quvchi kanali.
 *
 * <ul>
 *   <li>{@code app.notify.student-absent.enabled} ({@code APP_NOTIFY_STUDENT_ABSENT_ENABLED}) — {@code STUDENT_ABSENT};</li>
 *   <li>{@code app.notify.payment-overdue.enabled} ({@code APP_NOTIFY_PAYMENT_OVERDUE_ENABLED}) — {@code PAYMENT_OVERDUE}.</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "app.notify")
@Getter
@Setter
public class NotifyProperties {

    private Toggle studentAbsent = new Toggle();
    private Toggle paymentOverdue = new Toggle();

    @Getter
    @Setter
    public static class Toggle {
        private boolean enabled = false;
    }

    /** O'quvchi kanali shu tur uchun yoqiqmi; flag'i yo'q turlar — doim yoqiq. */
    public boolean studentChannelEnabled(NotificationType type) {
        return switch (type) {
            case STUDENT_ABSENT -> studentAbsent.isEnabled();
            case PAYMENT_OVERDUE -> paymentOverdue.isEnabled();
            default -> true;
        };
    }
}
