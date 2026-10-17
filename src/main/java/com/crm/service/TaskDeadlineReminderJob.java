package com.crm.service;

import com.crm.entity.Task;
import com.crm.entity.enums.TaskStatus;
import com.crm.notification.NotificationRequest;
import com.crm.notification.NotificationService;
import com.crm.notification.NotificationType;
import com.crm.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Vazifa muddati eslatmasi (docs/design/notifications.md §4) — har 15 daqiqada, egasiga qo'ng'iroqcha
 * (Telegram'ga ulangan bo'lsa botda ham):
 * <ul>
 *   <li>{@code TASK_DUE_SOON} — ochiq vazifa muddatiga {@link #SOON} yoki kamroq qoldi ("kun davomida" vazifaga
 *       yo'q);</li>
 *   <li>{@code TASK_OVERDUE} — muddati o'tdi (oxirgi {@link #OVERDUE_LOOKBACK} ichida: yoqilgan kuni eski
 *       vazifalar to'lqini kelmasin).</li>
 * </ul>
 * Takror yo'q: {@code task:{id}:soon:{muddat}} / {@code task:{id}:overdue:{muddat}} — muddat surilsa (qayta
 * rejalashtirilsa) yangi muddat uchun yana eslatiladi. Flag: {@code app.tasks.reminder.enabled} (standart true).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TaskDeadlineReminderJob {

    static final Duration SOON = Duration.ofHours(1);
    static final Duration OVERDUE_LOOKBACK = Duration.ofHours(24);
    private static final DateTimeFormatter KEY_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final DateTimeFormatter SHOW_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final DateTimeFormatter SHOW_DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final TaskRepository taskRepository;
    private final NotificationService notificationService;
    private final Clock billingClock;

    @Value(value = "${app.tasks.reminder.enabled:true}")
    private boolean enabled;

    public record Result(int soon, int overdue, int failed) {
    }

    @Scheduled(cron = "${app.tasks.reminder.cron:0 */15 * * * *}", zone = "Asia/Tashkent")
    public void scheduled() {
        if (!enabled) {
            return;
        }
        try {
            Result r = run(LocalDateTime.now(billingClock));
            if (r.soon() + r.overdue() + r.failed() > 0) {
                log.info("Vazifa eslatmalari: {} yaqin, {} muddati o'tgan, {} xato", r.soon(), r.overdue(), r.failed());
            }
        } catch (RuntimeException e) {
            log.error("Vazifa eslatmalari yiqildi: {}", e.getMessage(), e);
        }
    }

    /** Bitta aylanish (testlanadi). Har vazifa — o'z tranzaksiyasida (dispatch). */
    public Result run(LocalDateTime now) {
        int soon = 0;
        int overdue = 0;
        int failed = 0;
        for (Task t : taskRepository.findOpenDueBetween(TaskStatus.OPEN, now, now.plus(SOON))) {
            if (Boolean.TRUE.equals(t.getAllDay())) {
                continue;   // "kun davomida" (23:59) — 23:00 dagi "1 soat qoldi" ma'nosiz; faqat muddati o'tgani
            }
            if (notify(t, NotificationType.TASK_DUE_SOON, "soon")) {
                soon++;
            } else {
                failed++;
            }
        }
        for (Task t : taskRepository.findOpenDueBetween(TaskStatus.OPEN, now.minus(OVERDUE_LOOKBACK), now)) {
            if (notify(t, NotificationType.TASK_OVERDUE, "overdue")) {
                overdue++;
            } else {
                failed++;
            }
        }
        return new Result(soon, overdue, failed);
    }

    private boolean notify(Task t, NotificationType type, String kind) {
        try {
            notificationService.dispatch(NotificationRequest.builder()
                .type(type)
                .staffUserId(t.getAssignedTo().getId())
                .arg(t.getTitle())
                .arg(due(t))
                .link("/tasks?task=" + t.getId())
                .entityType("Task")
                .entityId(t.getId())
                .dedupeKey(key(t, kind))
                .build());
            return true;
        } catch (RuntimeException e) {
            log.warn("Vazifa eslatmasi yuborilmadi (task={}): {}", t.getId(), e.getMessage());
            return false;
        }
    }

    static String key(Task t, String kind) {
        return "task:" + t.getId() + ":" + kind + ":" + t.getDueAt().format(KEY_TIME);
    }

    private static String due(Task t) {
        return Boolean.TRUE.equals(t.getAllDay())
            ? t.getDueAt().format(SHOW_DAY) + " (kun davomida)"
            : t.getDueAt().format(SHOW_TIME);
    }
}
