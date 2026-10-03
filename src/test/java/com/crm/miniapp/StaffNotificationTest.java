package com.crm.miniapp;

import com.crm.entity.GroupScheduleDay;
import com.crm.entity.User;
import com.crm.entity.UserNotification;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.UserRole;
import com.crm.notification.NotificationEvent;
import com.crm.notification.NotificationService;
import com.crm.notification.NotificationType;
import com.crm.notification.UserNotificationRetentionJob;
import com.crm.repository.GroupRepository;
import com.crm.repository.GroupScheduleDayRepository;
import com.crm.repository.UserNotificationRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.AbstractSubscribableChannel;
import org.springframework.messaging.support.ChannelInterceptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Xodim bildirishnomalari (CRM qo'ng'iroqchasi): REST — faqat o'zinikini, commit'dan keyin yozish, STOMP
 * {@code /user/{username}/queue/notifications}, hodisa manbalari, 90 kunlik tozalash.
 */
class StaffNotificationTest extends MiniAppItBase {

    @Autowired private NotificationService notificationService;
    @Autowired private UserNotificationRepository notificationRepository;
    @Autowired private UserNotificationRetentionJob retentionJob;
    @Autowired private GroupScheduleDayRepository scheduleDayRepository;
    @Autowired private GroupRepository groupRepository;
    @Autowired @Qualifier("brokerChannel") private AbstractSubscribableChannel brokerChannel;

    /** STOMP push'larini ushlash: broker kanaliga ketayotgan manzillar. */
    private final List<String> pushed = new CopyOnWriteArrayList<>();
    private final ChannelInterceptor capture = new ChannelInterceptor() {
        @Override
        public Message<?> preSend(Message<?> message, MessageChannel channel) {
            String destination = SimpMessageHeaderAccessor.getDestination(message.getHeaders());
            if (destination != null && destination.endsWith("/queue/notifications")) {
                pushed.add(destination);
            }
            return message;
        }
    };

    @BeforeEach
    void captureStomp() {
        brokerChannel.addInterceptor(capture);
    }

    @AfterEach
    void releaseStomp() {
        brokerChannel.removeInterceptor(capture);
    }

    private void notify(User to, String title) {
        inTx(() -> notificationService.publish(new NotificationEvent(NotificationType.LEAVE_DECIDED, Set.of(to.getId()),
            Set.of(), null, title, "body", "/leaves/1", "Leave", 1L, false)));
    }

    private List<UserNotification> of(User u) {
        return inTx(() -> notificationRepository.findByUserIdOrderByCreatedAtDescIdDesc(u.getId(),
            org.springframework.data.domain.PageRequest.ofSize(50)).getContent());
    }

    // ── REST: o'zinikini ko'rish va belgilash ─────────────────────────────

    @Test
    void rest_ownOnly_readAndReadAll() throws Exception {
        User alice = staff(UserRole.ACCOUNTANT, null);
        User bob = staff(UserRole.TEACHER, null);
        notify(alice, "Birinchi");
        clock.setDateTime(LocalDateTime.of(2026, 9, 15, 12, 5));
        notify(alice, "Ikkinchi");

        String body = mvc.perform(get("/api/notifications/me").with(as(alice))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content", hasSize(2)))
            .andExpect(jsonPath("$.data.content[0].title").value("Ikkinchi"))       // yangilari oldin
            .andExpect(jsonPath("$.data.content[0].read").value(false))
            .andExpect(jsonPath("$.data.totalElements").value(2))
            .andReturn().getResponse().getContentAsString();
        long first = ((Number) JsonPath.read(body, "$.data.content[1].id")).longValue();
        mvc.perform(get("/api/notifications/me").param("size", "1").with(as(alice)))
            .andExpect(jsonPath("$.data.content", hasSize(1))).andExpect(jsonPath("$.data.totalPages").value(2));
        mvc.perform(get("/api/notifications/me/unread-count").with(as(alice))).andExpect(jsonPath("$.data.count").value(2));

        // Begona — 404, o'zgarmaydi
        mvc.perform(get("/api/notifications/me").with(as(bob))).andExpect(jsonPath("$.data.content", hasSize(0)));
        mvc.perform(post("/api/notifications/{id}/read", first).with(as(bob)))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("notification.notFound"));
        mvc.perform(post("/api/notifications/read-all").with(as(bob))).andExpect(jsonPath("$.data.updated").value(0));
        mvc.perform(get("/api/notifications/me/unread-count").with(as(alice))).andExpect(jsonPath("$.data.count").value(2));

        mvc.perform(post("/api/notifications/{id}/read", first).with(as(alice))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.read").value(true));
        mvc.perform(post("/api/notifications/{id}/read", first).with(as(alice))).andExpect(status().isOk()); // idempotent
        mvc.perform(get("/api/notifications/me/unread-count").with(as(alice))).andExpect(jsonPath("$.data.count").value(1));
        mvc.perform(post("/api/notifications/read-all").with(as(alice))).andExpect(jsonPath("$.data.updated").value(1));
        mvc.perform(get("/api/notifications/me/unread-count").with(as(alice))).andExpect(jsonPath("$.data.count").value(0));

        mvc.perform(get("/api/notifications/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void afterCommitOnly_andStompPush() {
        User alice = staff(UserRole.ADMIN, null);
        // Rollback — bildirishnoma ham, push ham yo'q
        tx.executeWithoutResult(s -> {
            notificationService.publish(new NotificationEvent(NotificationType.LEAVE_REQUEST, Set.of(alice.getId()),
                Set.of(), null, "Rollback", null, null, null, null, false));
            s.setRollbackOnly();
        });
        assertThat(of(alice)).isEmpty();
        assertThat(pushed).isEmpty();

        notify(alice, "Commit");
        assertThat(of(alice)).singleElement().satisfies(n -> assertThat(n.getTitle()).isEqualTo("Commit"));
        assertThat(pushed).containsExactly("/user/" + alice.getUsername() + "/queue/notifications");
    }

    @Test
    void retention_90days() {
        User alice = staff(UserRole.ADMIN, null);
        notify(alice, "Eski");
        clock.setDate(LocalDate.of(2026, 12, 20));       // +96 kun
        notify(alice, "Yangi");
        assertThat(retentionJob.cleanup()).isGreaterThanOrEqualTo(1);
        assertThat(of(alice)).extracting(UserNotification::getTitle).containsExactly("Yangi");
    }

    // ── Hodisa manbalari ─────────────────────────────────────────────────

    @Test
    void appLinkRequest_toReviewers() throws Exception {
        User sa = staff(UserRole.SUPER_ADMIN, null);
        User admin = staff(UserRole.ADMIN, null);
        User head = staff(UserRole.SALES_HEAD, null);
        User manager = staff(UserRole.SALES_MANAGER, null);
        String phone = phone();
        student("Ali", "Karimov", phone, null);

        mvc.perform(post("/api/app/link/manual").contentType(MediaType.APPLICATION_JSON)
                .content("{\"initData\":" + json(initData(1601)) + ",\"phone\":" + json(phone) + "}"))
            .andExpect(status().isOk());

        for (User u : List.of(sa, admin, head)) {
            assertThat(of(u)).as(u.getRole().name()).singleElement().satisfies(n -> {
                assertThat(n.getType()).isEqualTo("APP_LINK_REQUEST");
                assertThat(n.getBody()).contains("O'quvchi: Ali Karimov");
                assertThat(n.getEntityType()).isEqualTo("AppLinkRequest");
            });
        }
        assertThat(of(manager)).isEmpty();
        assertThat(pushed).contains("/user/" + head.getUsername() + "/queue/notifications");
    }

    @Test
    void externalChat_toStaffMembers_collapsed() throws Exception {
        String parentPhone = phone();
        Long studentId = student("Ali", "Karimov", phone(), parentPhone);
        User teacherUser = staff(UserRole.TEACHER, null);
        Long teacherId = teacherProfile(teacherUser);
        Long groupId = fixtures.group(fixtures.course(700_000), GroupStatus.ACTIVE, teacherId);
        fixtures.enrollment(studentId, groupId).start(d("01.09.2026")).save();
        shareContact(1602, parentPhone);
        String token = appToken(1602);

        String body = mvc.perform(post("/api/app/chats").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"target\":\"TEACHER\",\"teacherUserId\":" + teacherUser.getId() + "}"))
            .andReturn().getResponse().getContentAsString();
        long chatId = ((Number) JsonPath.read(body, "$.data.id")).longValue();
        for (String text : List.of("Salom", "Uy vazifasi qaysi?")) {
            mvc.perform(post("/api/app/chats/{id}/messages", chatId).header("Authorization", bearer(token))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"text\":" + json(text) + "}"))
                .andExpect(status().isOk());
        }
        assertThat(of(teacherUser)).singleElement().satisfies(n -> {
            assertThat(n.getType()).isEqualTo("CHAT_EXTERNAL");
            assertThat(n.getTitle()).isEqualTo("Yangi xabar: Ota-ona: Ali Karimov");
            assertThat(n.getBody()).isEqualTo("Uy vazifasi qaysi?");
            assertThat(n.getEntityId()).isEqualTo(chatId);
        });
        // O'qilgandan keyingi xabar — yangi qator
        mvc.perform(post("/api/notifications/read-all").with(as(teacherUser))).andExpect(status().isOk());
        mvc.perform(post("/api/app/chats/{id}/messages", chatId).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Yana\"}"))
            .andExpect(status().isOk());
        assertThat(of(teacherUser)).hasSize(2);
    }

    @Test
    void absenceNotice_toGroupTeacher() throws Exception {
        String parentPhone = phone();
        Long studentId = student("Ali", "Karimov", phone(), parentPhone);
        User teacherUser = staff(UserRole.TEACHER, null);       // app'da ulanmagan — baribir CRM'da ko'radi
        Long groupId = fixtures.group(fixtures.course(700_000), GroupStatus.ACTIVE, teacherProfile(teacherUser));
        inTx(() -> scheduleDayRepository.save(GroupScheduleDay.builder()
            .group(groupRepository.findById(groupId).orElseThrow())
            .dayOfWeek("WEDNESDAY").startTime("18:30").endTime("20:00").build()));
        fixtures.enrollment(studentId, groupId).start(d("01.09.2026")).save();
        shareContact(1603, parentPhone);

        mvc.perform(post("/api/app/absence-notices").header("Authorization", bearer(appToken(1603)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"studentId\":" + studentId + ",\"groupId\":" + groupId
                    + ",\"lessonDate\":\"2026-09-16\",\"type\":\"LATE\",\"comment\":\"Tirbandlik\"}"))
            .andExpect(status().isOk());

        assertThat(of(teacherUser)).singleElement().satisfies(n -> {
            assertThat(n.getType()).isEqualTo("ABSENCE_NOTICE");
            assertThat(n.getTitle()).isEqualTo("Sabab bildirildi: Ali Karimov");
            assertThat(n.getBody()).contains("16.09.2026 18:30").contains("Kechikadi: Tirbandlik");
        });
    }

    @Test
    void attendanceUnlock_requestToAdmins_decisionToTeacher() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Tashkent"));
        clock.setDate(today);
        User admin = staff(UserRole.ADMIN, null);
        User teacherUser = staff(UserRole.TEACHER, null);
        Long groupId = fixtures.group(fixtures.course(700_000), GroupStatus.ACTIVE, teacherProfile(teacherUser));
        LocalDate yesterday = today.minusDays(1);

        String body = mvc.perform(post("/api/attendance/unlock-requests").with(as(teacherUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"groupId\":" + groupId + ",\"attendanceDate\":\"" + yesterday + "\",\"note\":\"Unutibman\"}"))
            .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
        long requestId = ((Number) JsonPath.read(body, "$.data.id")).longValue();

        assertThat(of(admin)).singleElement().satisfies(n -> {
            assertThat(n.getType()).isEqualTo("ATTENDANCE_UNLOCK_REQUEST");
            assertThat(n.getBody()).contains("Unutibman");
        });
        assertThat(of(teacherUser)).isEmpty();

        mvc.perform(patch("/api/attendance/unlock-requests/{id}/approve", requestId).with(as(admin)))
            .andExpect(status().isOk());
        assertThat(of(teacherUser)).singleElement().satisfies(n -> {
            assertThat(n.getType()).isEqualTo("ATTENDANCE_UNLOCK_DECIDED");
            assertThat(n.getTitle()).startsWith("Davomat ochildi");
        });
        assertThat(of(admin)).hasSize(1);       // o'z qarori — o'ziga yo'q
    }

    @Test
    void leave_requestToAdmins_decisionToEmployee() throws Exception {
        User sa = staff(UserRole.SUPER_ADMIN, null);
        User admin = staff(UserRole.ADMIN, null);
        User accountant = staff(UserRole.ACCOUNTANT, null);

        String body = mvc.perform(post("/api/leaves").with(as(accountant)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"leaveType\":\"FAMILY\",\"fromDate\":\"2026-09-21\",\"toDate\":\"2026-09-22\",\"reason\":\"To'y\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long leaveId = ((Number) JsonPath.read(body, "$.data.id")).longValue();

        for (User u : List.of(sa, admin)) {
            assertThat(of(u)).singleElement().satisfies(n -> {
                assertThat(n.getType()).isEqualTo("LEAVE_REQUEST");
                assertThat(n.getBody()).isEqualTo("21.09.2026 — 22.09.2026");
                assertThat(n.getLink()).isEqualTo("/leaves/" + leaveId);
            });
        }
        assertThat(of(accountant)).isEmpty();

        mvc.perform(post("/api/leaves/{id}/reject", leaveId).with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"Hisobot davri\"}"))
            .andExpect(status().isOk());
        assertThat(of(accountant)).singleElement().satisfies(n -> {
            assertThat(n.getType()).isEqualTo("LEAVE_DECIDED");
            assertThat(n.getTitle()).isEqualTo("Ta'til rad etildi");
            assertThat(n.getBody()).contains("Hisobot davri");
        });
        assertThat(of(admin)).hasSize(1);
    }
}
