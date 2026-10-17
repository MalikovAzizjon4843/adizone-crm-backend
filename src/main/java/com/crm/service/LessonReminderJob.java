package com.crm.service;

import com.crm.dashboard.AttendanceMetricsService;
import com.crm.entity.Group;
import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.GroupStatus;
import com.crm.notification.DispatchResult;
import com.crm.notification.NotificationRequest;
import com.crm.notification.NotificationService;
import com.crm.notification.NotificationType;
import com.crm.notification.NotifyProperties;
import com.crm.repository.GroupRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.telegram.TelegramOutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Dars eslatmasi (docs/design/notifications.md §4) — har 15 daqiqada ({@code app.notify.lesson-reminder.cron}):
 * boshlanishiga {@code minutes-before} (standart 120) qolgan darslar, oyna {@code (now+M−15, now+M]}.
 *
 * <ul>
 *   <li>dars bor-yo'qligi — {@link GroupScheduleService#hasLessonOn} (bayram, CANCELLED / MOVED — yo'q; EXTRA,
 *       ko'chirilgan kun — bor), vaqt — {@link GroupScheduleService#slotOn};</li>
 *   <li>faqat ACTIVE guruh, guruh boshlanishi / tugashi oralig'ida;</li>
 *   <li>qabul qiluvchilar — shu kuni faol yozilmalar ({@link AttendanceMetricsService#isActiveOn}): sinov ham;
 *       muzlatilgan va chiqqan — yo'q;</li>
 *   <li>yuborish payti sokin soatga ({@code TelegramOutboxService.isQuiet}, 21:00–08:00) to'g'ri kelsa — umuman
 *       yuborilmaydi (kechikkan eslatma ma'nosiz);</li>
 *   <li>takror yo'q: {@code lesson:{groupId}:{sana}:{studentId}}. Flag {@code app.notify.lesson-reminder.enabled} — dispatch'da.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LessonReminderJob {

    /** Cron oralig'i bilan bir xil — har dars bir aylanishga tushadi. */
    static final int WINDOW_MINUTES = 15;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final GroupRepository groupRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final GroupScheduleService groupScheduleService;
    private final NotificationService notificationService;
    private final NotifyProperties properties;
    private final Clock billingClock;

    /** Natija: darslar, o'quvchilar, navbatga qo'yilgan, flag o'chiq, sokin soat sabab o'tkazildi. */
    public record Result(int lessons, int students, int queued, int disabled, boolean quiet) {
    }

    @Scheduled(cron = "${app.notify.lesson-reminder.cron:0 */15 * * * *}", zone = "Asia/Tashkent")
    public void scheduled() {
        try {
            Result r = run(LocalDateTime.now(billingClock));
            if (r.lessons() > 0) {
                log.info("Dars eslatmasi: {} dars, {} o'quvchi, {} navbatda, {} o'chiq (flag)", r.lessons(),
                    r.students(), r.queued(), r.disabled());
            }
        } catch (RuntimeException e) {
            log.error("Dars eslatmasi yiqildi: {}", e.getMessage(), e);
        }
    }

    /** Bitta aylanish (testlanadi). Har o'quvchi — o'z tranzaksiyasida (dispatch). */
    public Result run(LocalDateTime now) {
        if (TelegramOutboxService.isQuiet(now.toLocalTime())) {
            return new Result(0, 0, 0, 0, true);
        }
        LocalDateTime to = now.plusMinutes(Math.max(0, properties.getLessonReminder().getMinutesBefore()));
        LocalDateTime from = to.minusMinutes(WINDOW_MINUTES);
        Set<LocalDate> days = new LinkedHashSet<>();
        days.add(from.toLocalDate());
        days.add(to.toLocalDate());

        int lessons = 0;
        int students = 0;
        DispatchResult total = DispatchResult.EMPTY;
        for (Group group : groupRepository.findByStatus(GroupStatus.ACTIVE)) {
            for (LocalDate day : days) {
                LocalDateTime start = lessonStart(group, day).orElse(null);
                if (start == null || !start.isAfter(from) || start.isAfter(to)) {
                    continue;
                }
                lessons++;
                for (StudentGroup sg : studentGroupRepository.findActiveWithStudentByGroupId(group.getId())) {
                    if (!AttendanceMetricsService.isActiveOn(sg, day)) {
                        continue;
                    }
                    students++;
                    try {
                        total = total.plus(send(group, sg.getStudent(), day, start.toLocalTime()));
                    } catch (RuntimeException e) {
                        log.warn("Dars eslatmasi yuborilmadi (group={}, student={}): {}", group.getId(),
                            sg.getStudent().getId(), e.getMessage());
                    }
                }
            }
        }
        return new Result(lessons, students, total.telegram(), total.disabled(), false);
    }

    /** Shu kuni dars bo'lsa — boshlanish payti (guruh muddati, bayram, istisnolar hisobga olingan). */
    private Optional<LocalDateTime> lessonStart(Group group, LocalDate day) {
        if ((group.getStartDate() != null && day.isBefore(group.getStartDate()))
                || (group.getEndDate() != null && day.isAfter(group.getEndDate()))
                || !groupScheduleService.hasLessonOn(group.getId(), day)) {
            return Optional.empty();
        }
        return groupScheduleService.slotOn(group.getId(), day)
            .map(GroupScheduleService.LessonSlot::startTime)
            .flatMap(LessonReminderJob::time)
            .map(day::atTime);
    }

    private static Optional<LocalTime> time(String hhmm) {
        if (hhmm == null || hhmm.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalTime.parse(hhmm.length() == 4 ? "0" + hhmm : hhmm));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private DispatchResult send(Group group, Student student, LocalDate day, LocalTime start) {
        return notificationService.dispatch(NotificationRequest.builder()
            .type(NotificationType.LESSON_REMINDER)
            .studentId(student.getId())
            .arg(((student.getFirstName() != null ? student.getFirstName() : "") + " "
                + (student.getLastName() != null ? student.getLastName() : "")).trim())
            .arg(group.getGroupName())
            .arg(day.format(DAY))
            .arg(start.toString())
            .dedupeKey("lesson:" + group.getId() + ":" + day + ":" + student.getId())
            .appPath("/#/schedule")
            .build());
    }
}
