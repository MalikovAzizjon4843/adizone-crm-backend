package com.crm.miniapp;

import com.crm.dto.request.ChatSendRequest;
import com.crm.entity.Conversation;
import com.crm.entity.Message;
import com.crm.entity.TelegramOutbox;
import com.crm.entity.User;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.UserRole;
import com.crm.repository.ConversationRepository;
import com.crm.repository.MessageRepository;
import com.crm.service.ChatService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Chat ko'prigi (docs/design/telegram-platform.md §11.3). Bugun — 2026-09-15 12:00. */
class MiniAppChatTest extends MiniAppItBase {

    private static final long PARENT_TG = 1301;
    private static final long TEACHER_TG = 1302;

    @Autowired private ChatService chatService;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private MessageRepository messageRepository;

    private User teacherUser;
    private String parentToken;

    @BeforeEach
    void setUp() throws Exception {
        String parentPhone = phone();
        Long studentId = student("Ali", "Karimov", phone(), parentPhone);
        String teacherPhone = phone();
        teacherUser = staff(UserRole.TEACHER, teacherPhone);
        Long teacherId = teacherProfile(teacherUser);
        Long groupId = fixtures.group(fixtures.course(700_000), GroupStatus.ACTIVE, teacherId);
        fixtures.enrollment(studentId, groupId).start(d("01.09.2026")).save();
        shareContact(PARENT_TG, parentPhone);
        shareContact(TEACHER_TG, teacherPhone);
        parentToken = appToken(PARENT_TG);
        botApi.reset();
    }

    private ResultActions open(String token, String target, Long teacherUserId) throws Exception {
        return mvc.perform(post("/api/app/chats").header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"target\":\"" + target + "\"" + (teacherUserId != null ? ",\"teacherUserId\":" + teacherUserId : "") + "}"));
    }

    private long openTeacherChat() throws Exception {
        String body = open(parentToken, "TEACHER", teacherUser.getId()).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.data.id")).longValue();
    }

    private ResultActions sendText(String token, long conversationId, String text) throws Exception {
        return mvc.perform(post("/api/app/chats/{id}/messages", conversationId).header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON).content("{\"text\":" + json(text) + "}"));
    }

    private void staffReply(User staff, long conversationId, String text) {
        ChatSendRequest request = new ChatSendRequest();
        request.setConversationId(conversationId);
        request.setText(text);
        chatService.send(inTx(() -> userRepository.findById(staff.getId()).orElseThrow()), request);
    }

    @Test
    void contacts_andOpen_isIdempotent() throws Exception {
        mvc.perform(get("/api/app/chats/contacts").header("Authorization", bearer(parentToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data", hasSize(3)))
            .andExpect(jsonPath("$.data[0].target").value("TEACHER"))
            .andExpect(jsonPath("$.data[0].userId").value(teacherUser.getId()))
            .andExpect(jsonPath("$.data[0].groups", hasSize(1)))
            .andExpect(jsonPath("$.data[1].target").value("SUPPORT"))
            .andExpect(jsonPath("$.data[2].target").value("DIRECTOR"));

        long id = openTeacherChat();
        open(parentToken, "TEACHER", teacherUser.getId())
            .andExpect(jsonPath("$.data.id").value(id))
            .andExpect(jsonPath("$.data.side").value("CLIENT"))
            .andExpect(jsonPath("$.data.label").value("ustoz"))
            .andExpect(jsonPath("$.data.title").value(teacherUser.getFirstName() + " " + teacherUser.getLastName()));

        // Begona o'qituvchi va noto'g'ri manzil
        User stranger = staff(UserRole.TEACHER, null);
        teacherProfile(stranger);
        open(parentToken, "TEACHER", stranger.getId())
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("app.chat.forbidden"));
        open(parentToken, "BOSS", null).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("app.chat.targetInvalid"));
    }

    @Test
    void clientMessage_visibleInCrm_teacherPushed() throws Exception {
        long id = openTeacherChat();
        sendText(parentToken, id, "Assalomu alaykum, uy vazifasi qaysi?").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.mine").value(true))
            .andExpect(jsonPath("$.data.type").value("TEXT"));

        Message saved = inTx(() -> messageRepository.findLatest(id, org.springframework.data.domain.PageRequest.ofSize(1)).get(0));
        assertThat(saved.getSender()).isNull();
        assertThat(saved.getSenderAppIdentityId()).isNotNull();

        // CRM chat (xodim tomoni, mavjud API)
        mvc.perform(get("/api/chat/conversations").with(as(teacherUser))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].id").value(id))
            .andExpect(jsonPath("$.data[0].type").value("EXTERNAL"))
            .andExpect(jsonPath("$.data[0].title").value("Ota-ona: Ali Karimov"))
            .andExpect(jsonPath("$.data[0].externalTarget").value("TEACHER"))
            .andExpect(jsonPath("$.data[0].unreadCount").value(1))
            .andExpect(jsonPath("$.data[0].lastMessageSenderId").doesNotExist());
        mvc.perform(get("/api/chat/conversations/{id}/messages", id).with(as(teacherUser))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].senderType").value("APP"))
            .andExpect(jsonPath("$.data[0].senderName").value("Ota-ona: Ali Karimov"));
        mvc.perform(get("/api/chat/unread-count").with(as(teacherUser))).andExpect(status().isOk());

        // O'qituvchi app'da ulangan — unga push
        assertThat(outboxFor(TEACHER_TG)).singleElement()
            .satisfies(o -> assertThat(o.getText()).contains("Ota-ona: Ali Karimov").contains("uy vazifasi"));

        // CH-01: suhbatga a'zo bo'lmagan xodim — 403
        mvc.perform(get("/api/chat/conversations/{id}/messages", id).with(as(staff(UserRole.SALES_MANAGER, null))))
            .andExpect(status().isForbidden());
    }

    @Test
    void staffReply_pushToApp_unreadAndRead() throws Exception {
        long id = openTeacherChat();
        staffReply(teacherUser, id, "Salom! 12-dars, 40 ta so'z");

        assertThat(outboxFor(PARENT_TG)).singleElement().satisfies(o -> {
            assertThat(o.getText()).contains(teacherUser.getFirstName()).contains("ustoz").contains("12-dars");
            assertThat(o.getPriority()).isEqualTo(TelegramOutbox.Priority.NORMAL);
        });
        mvc.perform(get("/api/app/chats").header("Authorization", bearer(parentToken)))
            .andExpect(jsonPath("$.data[0].id").value(id))
            .andExpect(jsonPath("$.data[0].unread").value(1))
            .andExpect(jsonPath("$.data[0].lastMessageMine").value(false));
        String body = mvc.perform(get("/api/app/chats/{id}/messages", id).header("Authorization", bearer(parentToken)))
            .andExpect(jsonPath("$.data[0].mine").value(false))
            .andExpect(jsonPath("$.data[0].senderLabel").value("ustoz"))
            .andExpect(jsonPath("$.data[0].senderName").isString())
            .andReturn().getResponse().getContentAsString();
        long messageId = ((Number) JsonPath.read(body, "$.data[0].id")).longValue();

        mvc.perform(post("/api/app/chats/{id}/read", id).header("Authorization", bearer(parentToken))
                .contentType(MediaType.APPLICATION_JSON).content("{\"messageId\":" + messageId + "}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/app/chats").header("Authorization", bearer(parentToken)))
            .andExpect(jsonPath("$.data[0].unread").value(0));
    }

    @Test
    void quietHours_onePushAt0800() throws Exception {
        long id = openTeacherChat();
        clock.setDateTime(LocalDateTime.of(2026, 9, 15, 22, 0));
        staffReply(teacherUser, id, "Birinchi");
        staffReply(teacherUser, id, "Ikkinchi");
        clock.setDateTime(LocalDateTime.of(2026, 9, 16, 7, 30));
        staffReply(teacherUser, id, "Uchinchi");

        assertThat(outboxFor(PARENT_TG)).singleElement()
            .satisfies(o -> assertThat(o.getNotBefore()).isEqualTo(LocalDateTime.of(2026, 9, 16, 8, 0)));
        assertThat(outboxWorker.runOnce()).isZero();
        clock.setDateTime(LocalDateTime.of(2026, 9, 16, 8, 0));
        assertThat(outboxWorker.runOnce()).isEqualTo(1);
        assertThat(botApi.sent).singleElement().satisfies(s -> assertThat(s.chatId()).isEqualTo(PARENT_TG));

        // Kunduzi 5 daqiqalik oyna: ikki xabar — bitta push, oynadan keyin — yana bitta
        clock.setDateTime(LocalDateTime.of(2026, 9, 16, 10, 0));
        staffReply(teacherUser, id, "A");
        staffReply(teacherUser, id, "B");
        clock.setDateTime(LocalDateTime.of(2026, 9, 16, 10, 6));
        staffReply(teacherUser, id, "C");
        assertThat(outboxFor(PARENT_TG)).hasSize(3);
    }

    @Test
    void teacherMode_seesStaffSide_andReplies() throws Exception {
        long id = openTeacherChat();
        sendText(parentToken, id, "Savol").andExpect(status().isOk());
        String teacherToken = appToken(TEACHER_TG);

        mvc.perform(get("/api/app/chats").header("Authorization", bearer(teacherToken)))
            .andExpect(jsonPath("$.data[0].id").value(id))
            .andExpect(jsonPath("$.data[0].side").value("STAFF"))
            .andExpect(jsonPath("$.data[0].title").value("Ota-ona: Ali Karimov"))
            .andExpect(jsonPath("$.data[0].unread").value(1));
        sendText(teacherToken, id, "Javob").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.mine").value(true))
            .andExpect(jsonPath("$.data.senderLabel").value("ustoz"));

        Message reply = inTx(() -> messageRepository.findLatest(id, org.springframework.data.domain.PageRequest.ofSize(1)).get(0));
        assertThat(reply.getSender().getId()).isEqualTo(teacherUser.getId());
        assertThat(outboxFor(PARENT_TG)).hasSize(1);
    }

    @Test
    void supportChat_sharedQueueOfManagers() throws Exception {
        User admin = staff(UserRole.ADMIN, null);
        User head = staff(UserRole.SALES_HEAD, null);
        User director = staff(UserRole.SUPER_ADMIN, null);
        String body = open(parentToken, "SUPPORT", null).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.title").value("Menejer"))
            .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(body, "$.data.id")).longValue();
        sendText(parentToken, id, "To'lov qayerda qilinadi?").andExpect(status().isOk());

        for (User u : List.of(admin, head)) {
            mvc.perform(get("/api/chat/conversations").with(as(u)))
                .andExpect(jsonPath("$.data[*].id", hasItem((int) id)));
        }
        mvc.perform(get("/api/chat/conversations/{id}/messages", id).with(as(director))).andExpect(status().isForbidden());
        mvc.perform(get("/api/chat/conversations/{id}/messages", id).with(as(teacherUser))).andExpect(status().isForbidden());
        // Menejer javobi — app'ga push
        staffReply(admin, id, "Kassada, 9:00–18:00");
        assertThat(outboxFor(PARENT_TG)).singleElement()
            .satisfies(o -> assertThat(o.getText()).contains("menejer"));
    }

    @Test
    void idor_foreignChat_403_andClosed_409() throws Exception {
        long id = openTeacherChat();
        String otherPhone = phone();
        student("Boshqa", "Bola", phone(), otherPhone);
        shareContact(1303, otherPhone);
        String other = appToken(1303);

        mvc.perform(get("/api/app/chats/{id}/messages", id).header("Authorization", bearer(other)))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("app.chat.forbidden"));
        sendText(other, id, "salom").andExpect(status().isForbidden());
        mvc.perform(post("/api/app/chats/{id}/read", id).header("Authorization", bearer(other))
                .contentType(MediaType.APPLICATION_JSON).content("{\"messageId\":1}"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/app/chats").header("Authorization", bearer(other)))
            .andExpect(jsonPath("$.data", hasSize(0)));
        // Ichki (DIRECT) suhbat id si — 403
        User a = staff(UserRole.ADMIN, null);
        User b = staff(UserRole.ADMIN, null);
        long direct = inTx(() -> chatService.getOrCreateDirect(userRepository.findById(a.getId()).orElseThrow(), b.getId()).getId());
        mvc.perform(get("/api/app/chats/{id}/messages", direct).header("Authorization", bearer(parentToken)))
            .andExpect(status().isForbidden());

        // Faqat rasm
        mvc.perform(multipart("/api/app/chats/{id}/messages", id)
                .file(new MockMultipartFile("image", "a.txt", "text/plain", "x".getBytes()))
                .header("Authorization", bearer(parentToken)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("app.chat.imageOnly"));

        inTx(() -> {
            Conversation c = conversationRepository.findById(id).orElseThrow();
            c.setStatus("CLOSED");
        });
        sendText(parentToken, id, "salom").andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("chat.external.closed"));
    }
}
