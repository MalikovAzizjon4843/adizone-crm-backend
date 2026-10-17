package com.crm.miniapp;

import com.crm.billing.DebtorService;
import com.crm.dto.request.NoticeRequest;
import com.crm.dto.response.ExpectedPaymentsResponse;
import com.crm.entity.GroupScheduleDay;
import com.crm.entity.Holiday;
import com.crm.entity.LessonException;
import com.crm.entity.StudentGroup;
import com.crm.entity.TelegramOutbox;
import com.crm.entity.User;
import com.crm.entity.UserNotification;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.UserRole;
import com.crm.notification.DispatchResult;
import com.crm.notification.NotificationRequest;
import com.crm.notification.NotificationService;
import com.crm.notification.NotificationType;
import com.crm.notification.NotifyProperties;
import com.crm.repository.GroupRepository;
import com.crm.repository.GroupScheduleDayRepository;
import com.crm.repository.HolidayRepository;
import com.crm.repository.LessonExceptionRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.UserNotificationRepository;
import com.crm.service.LessonReminderJob;
import com.crm.service.NoticeService;
import com.crm.service.PaymentDueSoonJob;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O'quvchi / ota-ona kanali (docs/design/notifications.md §4, §6–§7): to'lov yaqinlashdi, dars eslatmasi,
 * Mini App e'lonlari va bildirishnoma sozlamalari. Flag'lar standart O'CHIQ — har test o'zi yoqadi.
 */
class StudentNotificationsTest extends MiniAppItBase {

    @Autowired private NotifyProperties notifyProperties;
    @Autowired private PaymentDueSoonJob dueSoonJob;
    @Autowired private DebtorService debtorService;
    @Autowired private LessonReminderJob lessonJob;
    @Autowired private GroupRepository groupRepository;
    @Autowired private GroupScheduleDayRepository scheduleDayRepository;
    @Autowired private StudentGroupRepository sgRepository;
    @Autowired private HolidayRepository holidayRepository;
    @Autowired private LessonExceptionRepository exceptionRepository;
    @Autowired private NoticeService noticeService;
    @Autowired private NotificationService notificationService;
    @Autowired private UserNotificationRepository notificationRepository;

    @AfterEach
    void flagsOff() {
        notifyProperties.getStudentAbsent().setEnabled(false);
        notifyProperties.getPaymentOverdue().setEnabled(false);
        notifyProperties.getPaymentDueSoon().setEnabled(false);
        notifyProperties.getPaymentDueSoon().setDaysBefore(3);
        notifyProperties.getLessonReminder().setEnabled(false);
        notifyProperties.getLessonReminder().setMinutesBefore(120);
        notifyProperties.getNoticeToStudents().setEnabled(false);
    }

    // ── TASK 1: to'lov yaqinlashdi ───────────────────────────────────────

    @Test
    void paymentDueSoon_sameAsExpectedPayments_oncePerDay_flagOffWritesNothing() throws Exception {
        String studentPhone = phone();
        Long studentId = student("Ali", "Karimov", studentPhone, null);
        Long groupId = fixtures.group(fixtures.course(630_000));
        fixtures.enrollment(studentId, groupId).start(d("15.10.2026")).save();
        shareContact(3101, studentPhone);
        LocalDate today = d("12.10.2026");
        clock.setDate(today);

        // Manba — kutilayotgan to'lovlar (R2): shu sana, shu summa
        ExpectedPaymentsResponse expected = debtorService.expected(d("15.10.2026"), d("15.10.2026"), today);
        assertThat(expected.getDays()).singleElement().satisfies(day ->
            assertThat(day.getStudents()).extracting(ExpectedPaymentsResponse.ExpectedStudent::getStudentId)
                .containsExactly(studentId));
        BigDecimal amount = expected.getDays().get(0).getStudents().get(0).getAmount();

        PaymentDueSoonJob.Summary off = dueSoonJob.run(today);
        assertThat(off.students()).isEqualTo(1);
        assertThat(off.disabled()).isEqualTo(1);
        assertThat(outboxFor(3101)).as("flag o'chiq — outbox bo'sh").isEmpty();

        notifyProperties.getPaymentDueSoon().setEnabled(true);
        PaymentDueSoonJob.Summary on = dueSoonJob.run(today);
        assertThat(on.queued()).isEqualTo(1);
        assertThat(outboxFor(3101)).singleElement().satisfies(o -> {
            assertThat(o.getEventCode()).isEqualTo("PAYMENT_DUE_SOON");
            assertThat(o.getDedupeKey()).startsWith("due:" + studentId + ":2026-10-15:i");
            assertThat(o.getText()).contains("To'lov yaqinlashdi").contains("15.10.2026")
                .contains(String.format(java.util.Locale.ROOT, "%,.0f", amount).replace(',', ' ') + " so'm");
            assertThat(o.getReplyMarkup()).contains("/#/payments");
        });

        PaymentDueSoonJob.Summary again = dueSoonJob.run(today);
        assertThat(again.queued()).isZero();
        assertThat(again.duplicates()).isEqualTo(1);
        assertThat(dueSoonJob.run(d("13.10.2026")).students()).as("ertasi — N kun emas").isZero();
    }

    // ── TASK 2: dars eslatmasi ───────────────────────────────────────────

    private Long lessonGroup(String day, String start) {
        Long groupId = fixtures.group(fixtures.course(700_000), GroupStatus.ACTIVE, fixtures.teacher());
        inTx(() -> scheduleDayRepository.save(GroupScheduleDay.builder()
            .group(groupRepository.findById(groupId).orElseThrow())
            .dayOfWeek(day).startTime(start).endTime("23:00").build()));
        return groupId;
    }

    @Test
    void lessonReminder_window_trialIncluded_frozenAndLeftExcluded_once() throws Exception {
        notifyProperties.getLessonReminder().setEnabled(true);
        Long groupId = lessonGroup("MONDAY", "16:00");
        String p1 = phone();
        String p2 = phone();
        String p3 = phone();
        String p4 = phone();
        Long active = student("Ali", "Karimov", p1, null);
        Long trial = student("Vali", "Sinov", p2, null);
        Long frozen = student("Gani", "Muzlagan", p3, null);
        Long left = student("Soli", "Ketgan", p4, null);
        fixtures.enrollment(active, groupId).start(d("01.10.2026")).save();
        fixtures.enrollment(trial, groupId).start(d("01.10.2026")).trial().save();
        Long frozenSg = fixtures.enrollment(frozen, groupId).start(d("01.10.2026")).save();
        Long leftSg = fixtures.enrollment(left, groupId).start(d("01.10.2026")).save();
        inTx(() -> {
            StudentGroup f = sgRepository.findById(frozenSg).orElseThrow();
            f.setFrozenFrom(d("10.10.2026"));
            StudentGroup l = sgRepository.findById(leftSg).orElseThrow();
            l.setIsActive(false);
            l.setLeaveDate(d("10.10.2026"));
        });
        shareContact(3201, p1);
        shareContact(3202, p2);
        shareContact(3203, p3);
        shareContact(3204, p4);

        // 19.10.2026 — dushanba; 16:00 dars, 120 daqiqa oldin → 14:00 dagi aylanish (oyna 13:45..14:00 → 15:45..16:00]
        assertThat(lessonJob.run(LocalDateTime.of(2026, 10, 19, 13, 45)).lessons()).as("hali erta").isZero();
        LessonReminderJob.Result r = lessonJob.run(LocalDateTime.of(2026, 10, 19, 14, 0));
        assertThat(r.lessons()).isEqualTo(1);
        assertThat(r.students()).as("faol + sinov").isEqualTo(2);
        assertThat(outboxFor(3201)).singleElement().satisfies(o -> {
            assertThat(o.getEventCode()).isEqualTo("LESSON_REMINDER");
            assertThat(o.getDedupeKey()).startsWith("lesson:" + groupId + ":2026-10-19:" + active + ":i");
            assertThat(o.getText()).contains("Dars eslatmasi").contains("19.10.2026").contains("16:00");
        });
        assertThat(outboxFor(3202)).as("sinov yozilmasi").hasSize(1);
        assertThat(outboxFor(3203)).as("muzlatilgan").isEmpty();
        assertThat(outboxFor(3204)).as("chiqqan").isEmpty();

        lessonJob.run(LocalDateTime.of(2026, 10, 19, 14, 0));
        assertThat(outboxFor(3201)).as("takror yo'q").hasSize(1);
        assertThat(lessonJob.run(LocalDateTime.of(2026, 10, 19, 14, 15)).lessons()).as("oyna o'tdi").isZero();
    }

    @Test
    void lessonReminder_holidayCancelledQuietHoursAndFlagOff() throws Exception {
        Long groupId = lessonGroup("MONDAY", "09:30");
        String p = phone();
        Long studentId = student("Ali", "Karimov", p, null);
        fixtures.enrollment(studentId, groupId).start(d("01.10.2026")).save();
        shareContact(3301, p);
        notifyProperties.getLessonReminder().setEnabled(true);

        // 09:30 dars → 07:30 da eslatish kerak edi: sokin soat — yuborilmaydi (kechikkan eslatma ma'nosiz)
        LessonReminderJob.Result quiet = lessonJob.run(LocalDateTime.of(2026, 10, 19, 7, 30));
        assertThat(quiet.quiet()).isTrue();
        assertThat(outboxFor(3301)).isEmpty();

        notifyProperties.getLessonReminder().setMinutesBefore(60);
        inTx(() -> holidayRepository.save(Holiday.builder().holidayDate(d("26.10.2026")).name("Bayram")
            .createdAt(LocalDateTime.now(clock)).build()));
        assertThat(lessonJob.run(LocalDateTime.of(2026, 10, 26, 8, 30)).lessons()).as("bayram").isZero();
        inTx(() -> exceptionRepository.save(LessonException.builder().groupId(groupId).lessonDate(d("02.11.2026"))
            .kind(LessonException.Kind.CANCELLED).reason("Bekor").createdAt(LocalDateTime.now(clock)).build()));
        assertThat(lessonJob.run(LocalDateTime.of(2026, 11, 2, 8, 30)).lessons()).as("bekor qilingan").isZero();

        notifyProperties.getLessonReminder().setEnabled(false);
        LessonReminderJob.Result off = lessonJob.run(LocalDateTime.of(2026, 11, 9, 8, 30));
        assertThat(off.lessons()).isEqualTo(1);
        assertThat(off.disabled()).isEqualTo(1);
        assertThat(outboxFor(3301)).as("flag o'chiq").isEmpty();
    }

    // ── TASK 3: Mini App e'lonlari ───────────────────────────────────────

    private Long notice(String title, List<UserRole> roles) {
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        NoticeRequest r = new NoticeRequest();
        r.setTitle(title);
        r.setContent("Matn: " + title);
        r.setTargetRoles(roles);
        return noticeService.createNotice(r).getId();
    }

    @Test
    void appNotices_onlyTargetedAudience_readFlag_publishDispatchesToParents() throws Exception {
        notifyProperties.getNoticeToStudents().setEnabled(true);
        String studentPhone = phone();
        String parentPhone = phone();
        Long studentId = student("Ali", "Karimov", studentPhone, parentPhone);
        shareContact(3401, parentPhone);
        shareContact(3402, studentPhone);
        User teacher = staff(UserRole.TEACHER, null);

        Long forParents = notice("Ota-onalar yig'ilishi", List.of(UserRole.PARENT));
        Long forAll = notice("Xodimlar uchun", List.of());            // "hamma" — o'quvchiga ko'rinmaydi
        Long forStudents = notice("Imtihon", List.of(UserRole.STUDENT, UserRole.TEACHER));

        // Bot: ota-onaga faqat PARENT e'loni, o'quvchiga faqat STUDENT e'loni (bir marta)
        assertThat(outboxFor(3401)).singleElement().satisfies(o -> {
            assertThat(o.getEventCode()).isEqualTo("NOTICE_APP");
            assertThat(o.getText()).contains("Ota-onalar yig'ilishi");
            assertThat(o.getReplyMarkup()).contains("/#/notices");
            assertThat(o.getDedupeKey()).isEqualTo("notice-app:" + forParents + ":i"
                + identityRepository.findByTelegramUserId(3401L).orElseThrow().getId());
        });
        assertThat(outboxFor(3402)).singleElement().satisfies(o -> assertThat(o.getText()).contains("Imtihon"));
        // Xodim qo'ng'iroqchasi: PARENT-only e'lon xodimga emas; "hamma" va STUDENT+TEACHER — o'qituvchiga
        List<UserNotification> bells = inTx(() -> notificationRepository
            .findByUserIdOrderByCreatedAtDescIdDesc(teacher.getId(), Pageable.unpaged()).getContent());
        assertThat(bells).extracting(UserNotification::getEntityId).containsExactlyInAnyOrder(forAll, forStudents);

        String parentToken = appToken(3401);
        mvc.perform(get("/api/app/notices").header("Authorization", bearer(parentToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].id").value(forParents))
            .andExpect(jsonPath("$.data.items[0].read").value(false))
            .andExpect(jsonPath("$.data.unread").value(1));
        mvc.perform(post("/api/app/notices/{id}/read", forParents).header("Authorization", bearer(parentToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].read").value(true))
            .andExpect(jsonPath("$.data.unread").value(0));
        mvc.perform(post("/api/app/notices/{id}/read", forStudents).header("Authorization", bearer(parentToken)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("app.notice.notFound"));

        mvc.perform(get("/api/app/notices").header("Authorization", bearer(appToken(3402))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].id").value(forStudents));
        assertThat(studentId).isNotNull();
    }

    @Test
    void appNotices_flagOff_visibleInAppButNoBot() throws Exception {
        String parentPhone = phone();
        student("Ali", "Karimov", phone(), parentPhone);
        shareContact(3501, parentPhone);
        Long id = notice("Yig'ilish", List.of(UserRole.PARENT));
        assertThat(outboxFor(3501)).as("notice-to-students o'chiq").isEmpty();
        mvc.perform(get("/api/app/notices").header("Authorization", bearer(appToken(3501))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].id").value(id));
    }

    // ── TASK 4: bildirishnoma sozlamalari ────────────────────────────────

    @Test
    void settings_defaultsOn_partialUpdate_unknownTopic400_dispatchRespectsOptOut() throws Exception {
        String parentPhone = phone();
        Long studentId = student("Ali", "Karimov", phone(), parentPhone);
        shareContact(3601, parentPhone);
        String token = appToken(3601);
        notifyProperties.getLessonReminder().setEnabled(true);
        notifyProperties.getPaymentOverdue().setEnabled(true);

        mvc.perform(get("/api/app/notification-settings").header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(5))
            .andExpect(jsonPath("$.data[?(@.topic=='lesson')].enabled").value(true))
            .andExpect(jsonPath("$.data[?(@.topic=='lesson')].available").value(true))
            .andExpect(jsonPath("$.data[?(@.topic=='notice')].available").value(false));

        mvc.perform(put("/api/app/notification-settings").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"lesson\":false}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[?(@.topic=='lesson')].enabled").value(false))
            .andExpect(jsonPath("$.data[?(@.topic=='absent')].enabled").value(true));
        mvc.perform(put("/api/app/notification-settings").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"sms\":false}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("app.notifySettings.unknownTopic"));

        DispatchResult lesson = inTx(() -> notificationService.dispatch(NotificationRequest.builder()
            .type(NotificationType.LESSON_REMINDER).studentId(studentId)
            .arg("Ali Karimov").arg("Ingliz tili").arg("19.10.2026").arg("16:00")
            .dedupeKey("lesson:test").build()));
        assertThat(lesson.optedOut()).as("foydalanuvchi o'chirgan").isEqualTo(1);
        assertThat(lesson.telegram()).isZero();

        DispatchResult overdue = inTx(() -> notificationService.dispatch(NotificationRequest.builder()
            .type(NotificationType.PAYMENT_OVERDUE).studentId(studentId)
            .arg("Ali Karimov").arg("Ingliz tili").arg("3").arg("630 000")
            .dedupeKey("pay:test").build()));
        assertThat(overdue.telegram()).as("boshqa mavzu — yoqiq").isEqualTo(1);
        assertThat(outboxFor(3601)).extracting(TelegramOutbox::getEventCode).containsExactly("PAYMENT_OVERDUE");

        String body = mvc.perform(get("/api/app/notification-settings").header("Authorization", bearer(token)))
            .andReturn().getResponse().getContentAsString();
        List<String> off = JsonPath.read(body, "$.data[?(@.enabled==false)].topic");
        assertThat(off).containsExactly("lesson");
    }
}
