package com.crm.notification;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * O'quvchi / ota-onaga ketadigan bot xabarlari flag'lari (docs/design/notifications.md §4.1). Hammasi standart
 * O'CHIQ: prodda shablon va qabul qiluvchilar tekshirilgandan keyin env bilan yoqiladi. Xodimlarning
 * qo'ng'iroqchasiga ta'sir qilmaydi — faqat {@link NotificationService#dispatch} dagi o'quvchi kanali.
 *
 * <ul>
 *   <li>{@code app.notify.student-absent.enabled} — {@code STUDENT_ABSENT};</li>
 *   <li>{@code app.notify.payment-overdue.enabled}, {@code .repeat-days} (1 — har kuni) —
 *       {@code PAYMENT_OVERDUE};</li>
 *   <li>{@code app.notify.payment-due-soon.enabled}, {@code .days-before} (3), {@code .cron} — {@code PAYMENT_DUE_SOON};</li>
 *   <li>{@code app.notify.lesson-reminder.enabled}, {@code .minutes-before} (120), {@code .cron} — {@code LESSON_REMINDER};</li>
 *   <li>{@code app.notify.notice-to-students.enabled} — {@code NOTICE_APP}.</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "app.notify")
@Getter
@Setter
public class NotifyProperties {

    private Toggle studentAbsent = new Toggle();
    private PaymentOverdue paymentOverdue = new PaymentOverdue();
    private PaymentDueSoon paymentDueSoon = new PaymentDueSoon();
    private LessonReminder lessonReminder = new LessonReminder();
    private Toggle noticeToStudents = new Toggle();

    @Getter
    @Setter
    public static class Toggle {
        private boolean enabled = false;
    }

    @Getter
    @Setter
    public static class PaymentOverdue extends Toggle {
        /** Qayta yuborish oralig'i (kun): OVERDUE bo'lgan kuni va keyin har shuncha kunda; 1 — har kuni. */
        private int repeatDays = 1;
    }

    @Getter
    @Setter
    public static class PaymentDueSoon extends Toggle {
        /** Keyingi to'lov sanasi = bugun + shuncha kun. */
        private int daysBefore = 3;
    }

    @Getter
    @Setter
    public static class LessonReminder extends Toggle {
        /** Dars boshlanishidan shuncha daqiqa oldin. */
        private int minutesBefore = 120;
    }

    /** O'quvchi kanali shu tur uchun yoqiqmi; flag'i yo'q turlar — doim yoqiq. */
    public boolean studentChannelEnabled(NotificationType type) {
        return switch (type) {
            case STUDENT_ABSENT -> studentAbsent.isEnabled();
            case PAYMENT_OVERDUE -> paymentOverdue.isEnabled();
            case PAYMENT_DUE_SOON -> paymentDueSoon.isEnabled();
            case LESSON_REMINDER -> lessonReminder.isEnabled();
            case NOTICE_APP -> noticeToStudents.isEnabled();
            default -> true;
        };
    }
}
