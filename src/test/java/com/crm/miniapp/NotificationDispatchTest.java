package com.crm.miniapp;

import com.crm.billing.AccrualService;
import com.crm.billing.BillingSnapshotService;
import com.crm.dto.request.ChatReadRequest;
import com.crm.dto.request.ChatSendRequest;
import com.crm.dto.request.LeadAssignRequest;
import com.crm.dto.request.LeadBulkAssignRequest;
import com.crm.dto.request.LeadCreateRequest;
import com.crm.dto.request.NoticeRequest;
import com.crm.dto.response.ChatMessageResponse;
import com.crm.entity.AppIdentity;
import com.crm.entity.AppIdentityStudent;
import com.crm.entity.GroupScheduleDay;
import com.crm.entity.Lead;
import com.crm.entity.Task;
import com.crm.entity.TelegramOutbox;
import com.crm.entity.User;
import com.crm.entity.UserNotification;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.TaskStatus;
import com.crm.entity.enums.UserRole;
import com.crm.notification.DispatchResult;
import com.crm.notification.NotificationRequest;
import com.crm.notification.NotificationService;
import com.crm.notification.NotificationType;
import com.crm.notification.NotifyProperties;
import com.crm.repository.GroupRepository;
import com.crm.repository.GroupScheduleDayRepository;
import com.crm.repository.LeadRepository;
import com.crm.repository.TaskRepository;
import com.crm.repository.UserNotificationRepository;
import com.crm.service.ChatService;
import com.crm.service.LeadBulkAssignService;
import com.crm.service.LeadService;
import com.crm.service.LeadSettingsService;
import com.crm.service.NoticeService;
import com.crm.service.PaymentReminderService;
import com.crm.service.TaskDeadlineReminderJob;
import com.crm.telegram.SendResult;
import com.crm.telegram.MessageOrigin;
import com.crm.telegram.TelegramOutboxService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Yagona bildirishnoma xizmati (docs/design/notifications.md): dispatch kanallari, dedupe, davomat tuzatilganda
 * bekor, qarz eslatmasi bir kunda bir marta, Telegram 403/429, yangi qo'ng'iroqcha turlari, vazifa muddati.
 */
class NotificationDispatchTest extends MiniAppItBase {

    @Autowired private NotificationService notificationService;
    @Autowired private UserNotificationRepository notificationRepository;
    @Autowired private TelegramOutboxService outboxService;
    @Autowired private GroupScheduleDayRepository scheduleDayRepository;
    @Autowired private GroupRepository groupRepository;
    @Autowired private PaymentReminderService paymentReminders;
    @Autowired private AccrualService accrual;
    @Autowired private BillingSnapshotService snapshots;
    @Autowired private ChatService chatService;
    @Autowired private LeadService leadService;
    @Autowired private LeadBulkAssignService bulkAssignService;
    @Autowired private LeadSettingsService leadSettingsService;
    @Autowired private LeadRepository leadRepository;
    @Autowired private NoticeService noticeService;
    @Autowired private TaskRepository taskRepository;
    @Autowired private TaskDeadlineReminderJob taskJob;
    @Autowired private NotifyProperties notifyProperties;

    /** Bu klassdagi testlar o'quvchi kanalini kutadi — flag'lar yoqiladi (standart o'chiq); oxirida qaytariladi. */
    @BeforeEach
    void enableStudentChannel() {
        setStudentFlags(true);
    }

    @AfterEach
    void restoreStudentFlags() {
        setStudentFlags(false);
    }

    private void setStudentFlags(boolean enabled) {
        notifyProperties.getStudentAbsent().setEnabled(enabled);
        notifyProperties.getPaymentOverdue().setEnabled(enabled);
    }

    // ── Yordamchilar ─────────────────────────────────────────────────────

    private List<UserNotification> of(User u) {
        return inTx(() -> notificationRepository.findByUserIdOrderByCreatedAtDescIdDesc(u.getId(), Pageable.unpaged())
            .getContent());
    }

    /** Bot orqali ulangan identity (to'g'ridan-to'g'ri bazaga): o'quvchilar — {@code relation} bilan. */
    private AppIdentity identity(long telegramId, AppIdentity.Kind kind, Long staffUserId, String language,
                                 AppIdentityStudent.Relation relation, Long... studentIds) {
        return inTx(() -> {
            AppIdentity i = identityRepository.save(AppIdentity.builder()
                .telegramUserId(telegramId).chatId(telegramId).kind(kind).phoneCanonical(phone())
                .staffUserId(staffUserId).language(language).linkedAt(LocalDateTime.now(clock)).build());
            for (Long studentId : studentIds) {
                identityStudentRepository.save(AppIdentityStudent.builder().identityId(i.getId()).studentId(studentId)
                    .relation(relation).linkedAt(LocalDateTime.now(clock)).build());
            }
            return i;
        });
    }

    private DispatchResult dispatch(NotificationRequest request) {
        return inTx(() -> notificationService.dispatch(request));
    }

    // ── TASK 0: kanallar va dedupe ───────────────────────────────────────

    @Test
    void staff_bellForAll_botOnlyForLinkedTeacher_inOwnLanguage_dedupe() {
        User teacher = staff(UserRole.TEACHER, null);
        User admin = staff(UserRole.ADMIN, null);
        identity(2101, AppIdentity.Kind.TEACHER, teacher.getId(), "ru", AppIdentityStudent.Relation.SELF);
        NotificationRequest request = NotificationRequest.builder()
            .type(NotificationType.NOTICE_NEW)
            .staffUserId(teacher.getId()).staffUserId(admin.getId())
            .arg("Bayram <tabrigi>").arg("Dushanba dam olish kuni")
            .entityType("Notice").entityId(999L)
            .dedupeKey("notice:999")
            .build();

        DispatchResult first = dispatch(request);

        assertThat(first).isEqualTo(new DispatchResult(2, 1, 0, 0));
        assertThat(of(teacher)).singleElement().satisfies(n -> {
            assertThat(n.getType()).isEqualTo("NOTICE_NEW");
            assertThat(n.getTitle()).isEqualTo("Yangi e'lon: Bayram <tabrigi>");
            assertThat(n.getBody()).isEqualTo("Dushanba dam olish kuni");
            assertThat(n.getDedupeKey()).isEqualTo("notice:999");
        });
        assertThat(of(admin)).hasSize(1);
        assertThat(outboxFor(2101)).singleElement().satisfies(o -> {
            // ru shablon (identity tili); argumentlar HTML-escape qilingan
            assertThat(o.getText()).isEqualTo("📢 <b>Bayram &lt;tabrigi&gt;</b>\n\nDushanba dam olish kuni");
            assertThat(o.getDedupeKey()).isEqualTo("notice:999:u" + teacher.getId());
            assertThat(o.getEventCode()).isEqualTo("NOTICE_NEW");
        });

        DispatchResult again = dispatch(request);
        assertThat(again.telegram()).isZero();
        assertThat(again.duplicates()).isEqualTo(1);
        assertThat(of(teacher)).as("qo'ng'iroqcha ham takrorlanmaydi").hasSize(1);
        assertThat(of(admin)).hasSize(1);
    }

    @Test
    void students_parentAndSelf_unlinkedCountedSilently_templatePerLanguage() {
        Long ali = student("Ali", "Karimov", phone(), null);
        Long vali = student("Vali", "Qodirov", phone(), null);
        identity(2201, AppIdentity.Kind.PARENT, null, "ru", AppIdentityStudent.Relation.PARENT, ali);
        identity(2202, AppIdentity.Kind.STUDENT, null, null, AppIdentityStudent.Relation.SELF, ali);

        DispatchResult r = dispatch(NotificationRequest.builder()
            .type(NotificationType.PAYMENT_OVERDUE)
            .studentId(ali).studentId(vali)
            .arg("Ali Karimov").arg("Ingliz tili").arg("3").arg("630 000")
            .dedupeKey("pay:test")
            .appPath("/#/payments")
            .build());

        assertThat(r).isEqualTo(new DispatchResult(0, 2, 0, 1));
        assertThat(outboxFor(2201)).singleElement().satisfies(o -> {
            assertThat(o.getText()).contains("Напоминание об оплате").contains("Долг: 630 000 сум");
            assertThat(o.getReplyMarkup()).contains("Открыть приложение").contains("https://webapp.example.test/#/payments");
        });
        assertThat(outboxFor(2202)).singleElement().satisfies(o ->
            assertThat(o.getText()).contains("To'lov eslatmasi").contains("Qarz: 630 000 so'm"));
    }

    // ── TASK 1: davomat ──────────────────────────────────────────────────

    private ResultActions mark(User as, Long groupId, LocalDate date, Long studentId, String status) throws Exception {
        String notes = "PRESENT".equals(status) ? "" : ",\"notes\":\"Kasal\"";
        return mvc.perform(post("/api/attendance/mark").with(as(as)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"groupId\":" + groupId + ",\"date\":\"" + date + "\",\"attendances\":[{\"studentId\":"
                + studentId + ",\"status\":\"" + status + "\"" + notes + "}]}"));
    }

    @Test
    void absent_queuedOnce_correctionCancelsUnsent_reAbsentRequeues_sentUntouched() throws Exception {
        LocalDate today = LocalDate.now();
        String parentPhone = phone();
        Long studentId = student("Ali", "Karimov", phone(), parentPhone);
        Long groupId = fixtures.group(fixtures.course(700_000), GroupStatus.ACTIVE, fixtures.teacher());
        inTx(() -> scheduleDayRepository.save(GroupScheduleDay.builder()
            .group(groupRepository.findById(groupId).orElseThrow())
            .dayOfWeek(today.getDayOfWeek().name()).startTime("00:00").endTime("00:01").build()));
        fixtures.enrollment(studentId, groupId).start(today.minusDays(7)).save();
        shareContact(2301, parentPhone);
        User admin = staff(UserRole.ADMIN, null);

        mark(admin, groupId, today, studentId, "ABSENT").andExpect(status().isOk());
        mark(admin, groupId, today, studentId, "ABSENT").andExpect(status().isOk());
        assertThat(outboxFor(2301)).as("qayta saqlash — takror emas").singleElement().satisfies(o -> {
            assertThat(o.getEventCode()).isEqualTo("STUDENT_ABSENT");
            assertThat(o.getPriority()).isEqualTo(TelegramOutbox.Priority.HIGH);
            assertThat(o.getDedupeKey()).startsWith("absent:").contains(":i");
            assertThat(o.getText()).contains("Ali Karimov").contains("kelmadi");
        });

        mark(admin, groupId, today, studentId, "PRESENT").andExpect(status().isOk());
        TelegramOutbox cancelled = outboxFor(2301).get(0);
        assertThat(cancelled.getStatus()).isEqualTo(TelegramOutbox.Status.CANCELLED);
        assertThat(cancelled.getDedupeKey()).endsWith(":x" + cancelled.getId());

        mark(admin, groupId, today, studentId, "ABSENT").andExpect(status().isOk());
        assertThat(outboxFor(2301)).hasSize(2);
        assertThat(outboxFor(2301).get(1).getStatus()).isEqualTo(TelegramOutbox.Status.PENDING);

        outboxWorker.runOnce();
        mark(admin, groupId, today, studentId, "PRESENT").andExpect(status().isOk());
        assertThat(outboxFor(2301).get(1).getStatus()).as("yuborilganiga tegilmaydi")
            .isEqualTo(TelegramOutbox.Status.SENT);
    }

    @Test
    void paymentReminder_oncePerStudentPerDay_unlinkedCounted() {
        Long linked = fixtures.student();
        Long unlinked = fixtures.student();
        Long group = fixtures.group(fixtures.course(630_000));
        Long sg1 = fixtures.enrollment(linked, group).start(d("15.10.2026")).save();
        Long sg2 = fixtures.enrollment(unlinked, group).start(d("15.10.2026")).save();
        clock.setDate(d("15.10.2026"));
        accrual.accrueUpTo(sg1, d("15.10.2026"));
        accrual.accrueUpTo(sg2, d("15.10.2026"));
        snapshots.refreshAllDue(d("15.10.2026"));
        identity(2401, AppIdentity.Kind.PARENT, null, null, AppIdentityStudent.Relation.PARENT, linked);

        PaymentReminderService.Summary first = paymentReminders.run(d("15.10.2026"));
        assertThat(first.students()).isEqualTo(2);
        assertThat(first.queued()).isEqualTo(1);
        assertThat(first.unlinked()).isEqualTo(1);
        assertThat(outboxFor(2401)).singleElement().satisfies(o -> {
            assertThat(o.getEventCode()).isEqualTo("PAYMENT_OVERDUE");
            assertThat(o.getDedupeKey()).startsWith("pay:" + linked + ":2026-10-15:i");
            assertThat(o.getText()).contains("630 000 so'm");
        });

        PaymentReminderService.Summary again = paymentReminders.run(d("15.10.2026"));
        assertThat(again.queued()).as("job qayta ishlasa ham bir kunda bir marta").isZero();
        assertThat(again.duplicates()).isEqualTo(1);
        assertThat(outboxFor(2401)).hasSize(1);
    }

    // ── Flag'lar: app.notify.student-absent / payment-overdue ────────────

    @Test
    void studentFlagsOff_nothingInOutbox_countedDisabled_staffBellUnaffected() {
        setStudentFlags(false);
        User admin = staff(UserRole.ADMIN, null);
        Long ali = student("Ali", "Karimov", phone(), null);
        identity(2601, AppIdentity.Kind.PARENT, null, null, AppIdentityStudent.Relation.PARENT, ali);

        DispatchResult r = dispatch(NotificationRequest.builder()
            .type(NotificationType.PAYMENT_OVERDUE)
            .studentId(ali)
            .staffUserId(admin.getId())
            .arg("Ali Karimov").arg("Ingliz tili").arg("3").arg("630 000")
            .dedupeKey("pay:off")
            .build());

        assertThat(r).isEqualTo(new DispatchResult(1, 0, 0, 0, 1));
        assertThat(outboxFor(2601)).as("o'chiq — outbox bo'sh").isEmpty();
        assertThat(of(admin)).as("xodim qo'ng'iroqchasiga ta'sir yo'q").singleElement()
            .satisfies(n -> assertThat(n.getType()).isEqualTo("PAYMENT_OVERDUE"));

        notifyProperties.getPaymentOverdue().setEnabled(true);
        DispatchResult on = dispatch(NotificationRequest.builder()
            .type(NotificationType.PAYMENT_OVERDUE)
            .studentId(ali)
            .arg("Ali Karimov").arg("Ingliz tili").arg("3").arg("630 000")
            .dedupeKey("pay:off")
            .build());
        assertThat(on).isEqualTo(new DispatchResult(0, 1, 0, 0, 0));
        assertThat(outboxFor(2601)).as("yoqiq — yoziladi").hasSize(1);
    }

    @Test
    void absentFlagOff_markAbsent_noOutbox_paymentFlagIndependent() throws Exception {
        notifyProperties.getStudentAbsent().setEnabled(false);
        LocalDate today = LocalDate.now();
        String parentPhone = phone();
        Long studentId = student("Ali", "Karimov", phone(), parentPhone);
        Long groupId = fixtures.group(fixtures.course(700_000), GroupStatus.ACTIVE, fixtures.teacher());
        inTx(() -> scheduleDayRepository.save(GroupScheduleDay.builder()
            .group(groupRepository.findById(groupId).orElseThrow())
            .dayOfWeek(today.getDayOfWeek().name()).startTime("00:00").endTime("00:01").build()));
        fixtures.enrollment(studentId, groupId).start(today.minusDays(7)).save();
        shareContact(2602, parentPhone);
        User admin = staff(UserRole.ADMIN, null);

        mark(admin, groupId, today, studentId, "ABSENT").andExpect(status().isOk());
        assertThat(outboxFor(2602)).as("STUDENT_ABSENT o'chiq").isEmpty();

        notifyProperties.getStudentAbsent().setEnabled(true);
        mark(admin, groupId, today, studentId, "PRESENT").andExpect(status().isOk());
        mark(admin, groupId, today, studentId, "ABSENT").andExpect(status().isOk());
        assertThat(outboxFor(2602)).as("yoqilgandan keyin yoziladi").singleElement()
            .satisfies(o -> assertThat(o.getEventCode()).isEqualTo("STUDENT_ABSENT"));
    }

    @Test
    void paymentReminder_flagOff_summaryCountsDisabled_outboxEmpty() {
        notifyProperties.getPaymentOverdue().setEnabled(false);
        Long linked = fixtures.student();
        Long group = fixtures.group(fixtures.course(630_000));
        Long sg = fixtures.enrollment(linked, group).start(d("15.10.2026")).save();
        clock.setDate(d("15.10.2026"));
        accrual.accrueUpTo(sg, d("15.10.2026"));
        snapshots.refreshAllDue(d("15.10.2026"));
        identity(2603, AppIdentity.Kind.PARENT, null, null, AppIdentityStudent.Relation.PARENT, linked);

        PaymentReminderService.Summary off = paymentReminders.run(d("15.10.2026"));
        assertThat(off.students()).isEqualTo(1);
        assertThat(off.disabled()).isEqualTo(1);
        assertThat(off.queued()).isZero();
        assertThat(outboxFor(2603)).isEmpty();
    }

    // ── Sokin soatlar: AUTOMATIC / MANUAL (buyurtmachi 17.10.2026) ────────

    @Test
    void quietHours_automaticAbsentWaitsUntil0800_evenHigh() {
        Long ali = student("Ali", "Karimov", phone(), null);
        identity(2701, AppIdentity.Kind.PARENT, null, null, AppIdentityStudent.Relation.PARENT, ali);
        clock.setDateTime(LocalDateTime.of(2026, 9, 15, 22, 30));

        DispatchResult r = dispatch(NotificationRequest.builder()
            .type(NotificationType.STUDENT_ABSENT)
            .studentId(ali)
            .arg("Ali Karimov").arg("Ingliz tili").arg("15.09.2026").arg("")
            .dedupeKey("absent:night")
            .priority(TelegramOutbox.Priority.HIGH)
            .build());

        assertThat(r.telegram()).isEqualTo(1);
        assertThat(outboxFor(2701)).singleElement().satisfies(o -> {
            assertThat(o.getNotBefore()).as("tunda absent — 08:00 gacha kutadi")
                .isEqualTo(LocalDateTime.of(2026, 9, 16, 8, 0));
            assertThat(o.getSilent()).isFalse();
            assertThat(o.getPriority()).isEqualTo(TelegramOutbox.Priority.HIGH);
        });
        assertThat(outboxWorker.runOnce()).isZero();
    }

    @Test
    void quietHours_manualInternalChat_toLinkedTeacher_immediateSilent() {
        User admin = staff(UserRole.ADMIN, null);
        User teacher = staff(UserRole.TEACHER, null);
        identity(2702, AppIdentity.Kind.TEACHER, teacher.getId(), null, AppIdentityStudent.Relation.SELF);
        Long conversationId = inTx(() -> chatService.getOrCreateDirect(admin, teacher.getId()).getId());
        clock.setDateTime(LocalDateTime.of(2026, 9, 15, 23, 10));

        send(admin, conversationId, "Ertaga dars bormi?");

        assertThat(outboxFor(2702)).singleElement().satisfies(o -> {
            assertThat(o.getEventCode()).isEqualTo("CHAT_INTERNAL");
            assertThat(o.getNotBefore()).as("tunda chat — darhol").isEqualTo(LocalDateTime.of(2026, 9, 15, 23, 10));
            assertThat(o.getSilent()).isTrue();
        });
        assertThat(outboxWorker.runOnce()).isEqualTo(1);
    }

    // ── TASK 2: Telegram xatolari ────────────────────────────────────────

    @Test
    void telegram403_failsWithoutRetry_failsRestOfChat_unlinksIdentity() {
        Long studentId = student("Ali", "Karimov", phone(), null);
        AppIdentity identity = identity(2501, AppIdentity.Kind.PARENT, null, null,
            AppIdentityStudent.Relation.PARENT, studentId);
        inTx(() -> {
            outboxService.enqueue(2501, "bir", null, TelegramOutbox.Priority.HIGH, "t403:1", "TEST",
                MessageOrigin.AUTOMATIC);
            outboxService.enqueue(2501, "ikki", null, TelegramOutbox.Priority.HIGH, "t403:2", "TEST",
                MessageOrigin.AUTOMATIC);
        });
        botApi.nextResults.add(SendResult.error(403, "Forbidden: bot was blocked by the user", null));

        assertThat(outboxWorker.runOnce()).isZero();

        assertThat(botApi.sent).as("ikkinchisi yuborilmaydi").hasSize(1);
        assertThat(outboxFor(2501)).allSatisfy(o -> assertThat(o.getStatus()).isEqualTo(TelegramOutbox.Status.FAILED));
        assertThat(outboxFor(2501).get(0).getLastError()).isEqualTo("HTTP 403: Forbidden: bot was blocked by the user");
        assertThat(outboxFor(2501).get(0).getAttempts()).isEqualTo(1);
        AppIdentity after = inTx(() -> identityRepository.findById(identity.getId()).orElseThrow());
        assertThat(after.getStatus()).isEqualTo(AppIdentity.Status.UNLINKED);
        assertThat(after.getUnlinkReason()).isEqualTo("BOT_BLOCKED");
        assertThat(inTx(() -> identityStudentRepository.findByIdentityIdOrderByIdAsc(identity.getId()))).isEmpty();
    }

    @Test
    void telegram429_respectsRetryAfter_pausesWorker_attemptNotCounted() {
        inTx(() -> {
            outboxService.enqueue(2502, "bir", null, TelegramOutbox.Priority.HIGH, "t429:1", "TEST",
                MessageOrigin.AUTOMATIC);
            outboxService.enqueue(2502, "ikki", null, TelegramOutbox.Priority.HIGH, "t429:2", "TEST",
                MessageOrigin.AUTOMATIC);
        });
        botApi.nextResults.add(SendResult.error(429, "Too Many Requests: retry after 7", 7));
        LocalDateTime now = LocalDateTime.now(clock);

        assertThat(outboxWorker.runOnce()).isZero();
        TelegramOutbox first = outboxFor(2502).get(0);
        assertThat(first.getStatus()).isEqualTo(TelegramOutbox.Status.PENDING);
        assertThat(first.getAttempts()).isZero();
        assertThat(first.getNotBefore()).isEqualTo(now.plusSeconds(7));
        assertThat(first.getLastError()).startsWith("HTTP 429");
        assertThat(botApi.sent).as("aylanish to'xtadi").hasSize(1);

        assertThat(outboxWorker.runOnce()).as("retry_after tugaguncha hech narsa").isZero();
        assertThat(botApi.sent).hasSize(1);

        clock.setDateTime(now.plusSeconds(8));
        assertThat(outboxWorker.runOnce()).isEqualTo(2);
        assertThat(outboxFor(2502)).allSatisfy(o -> assertThat(o.getStatus()).isEqualTo(TelegramOutbox.Status.SENT));
    }

    @Test
    void telegram400_failsImmediately_noRetry() {
        inTx(() -> outboxService.enqueue(2503, "bir", null, TelegramOutbox.Priority.HIGH, "t400:1", "TEST",
            MessageOrigin.AUTOMATIC));
        botApi.nextResults.add(SendResult.error(400, "Bad Request: chat not found", null));
        outboxWorker.runOnce();
        assertThat(outboxFor(2503)).singleElement().satisfies(o -> {
            assertThat(o.getStatus()).isEqualTo(TelegramOutbox.Status.FAILED);
            assertThat(o.getLastError()).isEqualTo("HTTP 400: Bad Request: chat not found");
        });
    }

    // ── TASK 3: qo'ng'iroqcha turlari ────────────────────────────────────

    @Test
    void chatInternal_collapsed_notToSender_readWhenCaughtUp() {
        User ali = staff(UserRole.ADMIN, null);
        User vali = staff(UserRole.SALES_MANAGER, null);
        Long conversationId = inTx(() -> chatService.getOrCreateDirect(ali, vali.getId()).getId());

        send(ali, conversationId, "Salom");
        ChatMessageResponse last = send(ali, conversationId, "Lid bo'yicha gaplashamizmi?");

        assertThat(of(ali)).isEmpty();
        assertThat(of(vali)).singleElement().satisfies(n -> {
            assertThat(n.getType()).isEqualTo("CHAT_INTERNAL");
            assertThat(n.getTitle()).isEqualTo("Yangi xabar: " + ali.getFirstName() + " ADMIN");
            assertThat(n.getBody()).endsWith(": Lid bo'yicha gaplashamizmi?");
            assertThat(n.getEntityId()).isEqualTo(conversationId);
            assertThat(n.getReadAt()).isNull();
        });

        inTx(() -> {
            ChatReadRequest read = new ChatReadRequest();
            read.setConversationId(conversationId);
            read.setMessageId(last.getId());
            chatService.markRead(userRepository.findById(vali.getId()).orElseThrow(), read);
        });
        assertThat(of(vali).get(0).getReadAt()).as("suhbat oxirigacha o'qildi").isNotNull();
    }

    private ChatMessageResponse send(User sender, Long conversationId, String text) {
        return inTx(() -> {
            ChatSendRequest r = new ChatSendRequest();
            r.setConversationId(conversationId);
            r.setText(text);
            return chatService.send(userRepository.findById(sender.getId()).orElseThrow(), r);
        });
    }

    private Long lead(String name) {
        LeadCreateRequest r = new LeadCreateRequest();
        r.setFullName(name);
        r.setPhone(phone());
        return leadService.createLeadByStaff(r).getId();
    }

    @Test
    void leadAssigned_single_bulkAsOneSummary_auto() {
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        User one = staff(UserRole.SALES_MANAGER, null);
        User bulk = staff(UserRole.SALES_MANAGER, null);
        User auto = staff(UserRole.SALES_MANAGER, null);

        Long first = lead("Ali Valiyev");
        LeadAssignRequest single = new LeadAssignRequest();
        single.setUserId(one.getId());
        leadService.assignLead(first, single);
        leadService.assignLead(first, single);          // o'zgarmadi — yangi bildirishnoma yo'q
        assertThat(of(one)).singleElement().satisfies(n -> {
            assertThat(n.getType()).isEqualTo("LEAD_ASSIGNED");
            assertThat(n.getTitle()).isEqualTo("Sizga lid biriktirildi: Ali Valiyev");
            assertThat(n.getEntityId()).isEqualTo(first);
        });

        LeadBulkAssignRequest request = new LeadBulkAssignRequest();
        request.setLeadIds(List.of(lead("A"), lead("B"), lead("C")));
        request.setUserId(bulk.getId());
        bulkAssignService.bulkAssign(request);
        assertThat(of(bulk)).singleElement().satisfies(n -> assertThat(n.getTitle())
            .isEqualTo("Sizga 3 ta lid biriktirildi"));

        Long free = lead("Meta Lid");
        inTx(() -> {
            Lead l = leadRepository.findById(free).orElseThrow();
            l.setAssignedUser(null);
            leadSettingsService.assignIfUnassigned(l, userRepository.findById(auto.getId()).orElseThrow());
        });
        assertThat(of(auto)).singleElement().satisfies(n -> {
            assertThat(n.getTitle()).isEqualTo("Yangi lid biriktirildi: Meta Lid");
            assertThat(n.getBody()).startsWith("Manba: ");
        });
    }

    @Test
    void noticeNew_toAudienceRoles_onPublish_once() {
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        User teacher = staff(UserRole.TEACHER, null);
        User manager = staff(UserRole.SALES_MANAGER, null);
        NoticeRequest draft = new NoticeRequest();
        draft.setTitle("Metodik kengash");
        draft.setContent("Juma 15:00");
        draft.setTargetRoles(List.of(UserRole.TEACHER));
        draft.setIsPublished(false);
        Long id = noticeService.createNotice(draft).getId();
        assertThat(of(teacher)).as("qoralama — yo'q").isEmpty();

        draft.setIsPublished(true);
        noticeService.updateNotice(id, draft);
        noticeService.updateNotice(id, draft);
        assertThat(of(teacher)).singleElement().satisfies(n -> {
            assertThat(n.getType()).isEqualTo("NOTICE_NEW");
            assertThat(n.getTitle()).isEqualTo("Yangi e'lon: Metodik kengash");
        });
        assertThat(of(manager)).as("auditoriyada emas").isEmpty();
    }

    // ── TASK 4: vazifa muddati ───────────────────────────────────────────

    private Task task(User owner, LocalDateTime dueAt, TaskStatus status) {
        return inTx(() -> taskRepository.save(Task.builder()
            .title("Qo'ng'iroq: " + dueAt.toLocalTime())
            .dueAt(dueAt)
            .status(status)
            .assignedTo(userRepository.findById(owner.getId()).orElseThrow())
            .build()));
    }

    @Test
    void taskReminder_soonAndOverdue_once_ignoresDoneAndOld() {
        User owner = staff(UserRole.SALES_MANAGER, null);
        LocalDateTime now = LocalDateTime.of(2026, 10, 17, 14, 0);
        Task soon = task(owner, now.plusMinutes(40), TaskStatus.OPEN);
        Task overdue = task(owner, now.minusHours(2), TaskStatus.OPEN);
        task(owner, now.plusMinutes(20), TaskStatus.DONE);
        task(owner, now.minusHours(30), TaskStatus.OPEN);       // 24 soatdan eski — to'lqin yo'q
        task(owner, now.plusHours(3), TaskStatus.OPEN);         // hali erta

        TaskDeadlineReminderJob.Result r = taskJob.run(now);
        taskJob.run(now.plusMinutes(15));

        assertThat(r.soon()).isEqualTo(1);
        assertThat(r.overdue()).isEqualTo(1);
        List<UserNotification> bells = of(owner);
        assertThat(bells).as("ikkinchi aylanish takrorlamaydi").hasSize(2);
        assertThat(bells).extracting(UserNotification::getType)
            .containsExactlyInAnyOrder("TASK_DUE_SOON", "TASK_OVERDUE");
        assertThat(bells).extracting(UserNotification::getEntityId)
            .containsExactlyInAnyOrder(soon.getId(), overdue.getId());
        assertThat(bells).filteredOn(n -> n.getType().equals("TASK_DUE_SOON")).singleElement()
            .satisfies(n -> assertThat(n.getBody()).isEqualTo("Muddat: 17.10.2026 14:40"));

        // Muddat surildi — yangi muddat uchun yana eslatiladi
        inTx(() -> {
            Task t = taskRepository.findById(soon.getId()).orElseThrow();
            t.setDueAt(now.plusMinutes(50));
        });
        taskJob.run(now.plusMinutes(5));
        assertThat(of(owner)).hasSize(3);
    }

    /** Har {@code notify.*} kaliti uz, ru, en da bor; har tur uchun kamida title yoki telegram shabloni bor. */
    @Test
    void templates_sameNotifyKeysInAllBundles_everyTypeHasTemplate() throws Exception {
        Set<String> uz = notifyKeys("messages.properties");
        assertThat(notifyKeys("messages_ru.properties")).isEqualTo(uz);
        assertThat(notifyKeys("messages_en.properties")).isEqualTo(uz);
        for (NotificationType type : List.of(NotificationType.CHAT_INTERNAL, NotificationType.LEAD_ASSIGNED,
                NotificationType.NOTICE_NEW, NotificationType.TASK_DUE_SOON, NotificationType.TASK_OVERDUE,
                NotificationType.STUDENT_ABSENT, NotificationType.PAYMENT_OVERDUE, NotificationType.DIRECTOR_DIGEST)) {
            assertThat(uz).as(type.name()).contains(type.key("title"));
        }
    }

    private static Set<String> notifyKeys(String resource) throws Exception {
        Properties p = new Properties();
        try (Reader r = new InputStreamReader(
                NotificationDispatchTest.class.getClassLoader().getResourceAsStream(resource), StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return p.stringPropertyNames().stream().filter(k -> k.startsWith("notify.")).collect(Collectors.toSet());
    }
}
