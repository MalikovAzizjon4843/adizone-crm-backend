package com.crm.miniapp;

import com.crm.billing.support.RecordingTelegramBotApi;
import com.crm.entity.Appeal;
import com.crm.entity.AppealMessage;
import com.crm.entity.TelegramOutbox;
import com.crm.entity.User;
import com.crm.entity.UserNotification;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.UserRole;
import com.crm.repository.AppealMessageRepository;
import com.crm.repository.AppealRepository;
import com.crm.repository.BotConversationRepository;
import com.crm.repository.UserNotificationRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Taklif va shikoyatlar — murojaatlar (docs/design/telegram-platform.md §12): bot oqimi (ulangan, ulanmagan, anonim,
 * bekor qilish, limit, eskirish), anonimlik API'da sizmasligi, ruxsatlar, xodim javobi outbox'ga, reply orqali
 * SENDER xabari, Mini App.
 */
class AppealTest extends MiniAppItBase {

    @Autowired private AppealRepository appealRepository;
    @Autowired private AppealMessageRepository messageRepository;
    @Autowired private BotConversationRepository conversationRepository;
    @Autowired private UserNotificationRepository notificationRepository;

    // ── Yordamchilar ─────────────────────────────────────────────────────

    private void say(long tg, String text) throws Exception {
        webhook(textUpdate(nextUpdateId(), tg, text)).andExpect(status().isOk());
    }

    private void press(long tg, String data) throws Exception {
        String update = "{\"update_id\":" + nextUpdateId() + ",\"callback_query\":{\"id\":\"cb" + nextUpdateId() + "\","
            + "\"from\":{\"id\":" + tg + ",\"is_bot\":false,\"first_name\":\"Dilnoza\",\"language_code\":\"uz\"},"
            + "\"message\":{\"message_id\":5,\"chat\":{\"id\":" + tg + ",\"type\":\"private\"},\"date\":1,\"text\":\"x\"},"
            + "\"data\":" + json(data) + "}}";
        webhook(update).andExpect(status().isOk());
    }

    private void replyTo(long tg, String repliedBotText, String text) throws Exception {
        String update = "{\"update_id\":" + nextUpdateId() + ",\"message\":{\"message_id\":9,"
            + "\"from\":{\"id\":" + tg + ",\"is_bot\":false,\"first_name\":\"Dilnoza\"},"
            + "\"chat\":{\"id\":" + tg + ",\"type\":\"private\"},\"date\":1,\"text\":" + json(text) + ","
            + "\"reply_to_message\":{\"message_id\":7,\"from\":{\"id\":1,\"is_bot\":true,\"first_name\":\"Bot\"},"
            + "\"chat\":{\"id\":" + tg + ",\"type\":\"private\"},\"date\":1,\"text\":"
            + json(repliedBotText).replace("\n", "\\n") + "}}}";
        webhook(update).andExpect(status().isOk());
    }

    private String lastText() {
        return botApi.sent.get(botApi.sent.size() - 1).html();
    }

    private Map<String, Object> lastMarkup() {
        return botApi.sent.get(botApi.sent.size() - 1).replyMarkup();
    }

    private List<Appeal> appealsOf(long chatId) {
        return inTx(() -> appealRepository.findByTelegramChatIdOrderByCreatedAtDescIdDesc(chatId));
    }

    private List<UserNotification> bells(User u) {
        return inTx(() -> notificationRepository.findByUserIdOrderByCreatedAtDescIdDesc(u.getId(),
            PageRequest.ofSize(50)).getContent());
    }

    /** Botda to'liq oqim: tur → anonim → matn → yuborish (ulangan foydalanuvchi). */
    private Appeal submitLinked(long tg, String type, boolean anonymous, String text) throws Exception {
        say(tg, "/taklif");
        press(tg, "apl:t:" + type);
        press(tg, "apl:a:" + (anonymous ? "1" : "0"));
        say(tg, text);
        press(tg, "apl:ok");
        return appealsOf(tg).get(0);
    }

    private Appeal appeal(long chatId, Appeal.Type type, boolean anonymous, Long teacherId) {
        return inTx(() -> appealRepository.save(Appeal.builder()
            .number("MRJ-T" + System.nanoTime() % 1_000_000)
            .type(type).text("Matn").anonymous(anonymous).source(Appeal.Source.MINIAPP)
            .telegramChatId(chatId).teacherId(teacherId).status(Appeal.Status.NEW)
            .createdAt(LocalDateTime.now(clock)).updatedAt(LocalDateTime.now(clock)).build()));
    }

    // ── Bot: ulanmagan, ismi bilan ───────────────────────────────────────

    @Test
    @SuppressWarnings(value = "unchecked")
    void bot_unlinked_named_fullFlow() throws Exception {
        User sa = staff(UserRole.SUPER_ADMIN, null);
        long tg = 8_901;

        // /start menyusida murojaat tugmasi bor
        say(tg, "/start");
        List<List<Map<String, Object>>> inline =
            (List<List<Map<String, Object>>>) botApi.sent.get(0).replyMarkup().get("inline_keyboard");
        assertThat(inline).anySatisfy(row -> assertThat(row.get(0)).containsEntry("callback_data", "apl:new"));

        botApi.reset();
        say(tg, "/taklif");
        assertThat(lastText()).contains("Murojaat turini tanlang");
        assertThat(lastMarkup()).containsKey("inline_keyboard");
        press(tg, "apl:t:SUGGESTION");
        assertThat(lastText()).contains("Anonim yuborilsinmi");
        press(tg, "apl:a:0");
        assertThat(lastText()).contains("Ismingizni yozing");
        say(tg, "X");                                             // juda qisqa
        assertThat(lastText()).contains("2–150");
        say(tg, "Dilshod Aka");
        assertThat(lastText()).contains("Telefon raqamingizni");
        List<List<Map<String, Object>>> keyboard = (List<List<Map<String, Object>>>) lastMarkup().get("keyboard");
        assertThat(keyboard.get(0).get(0)).containsEntry("request_contact", true);
        webhook(contactUpdate(nextUpdateId(), tg, 999L, "998901112233")).andExpect(status().isOk()); // begona kontakt
        assertThat(lastText()).contains("o'zingizning raqamingizni");
        String phone = phone();
        webhook(contactUpdate(nextUpdateId(), tg, tg, phone.substring(1))).andExpect(status().isOk());
        assertThat(lastText()).contains("Murojaat matnini yozing");
        say(tg, "Dars jadvalini <b>ertaroq</b> e'lon qiling");
        assertThat(lastText()).contains("Tekshiring").contains("&lt;b&gt;ertaroq&lt;/b&gt;").contains("Dilshod Aka");
        press(tg, "apl:ok");
        assertThat(lastText()).matches("(?s)✅ Qabul qilindi, №MRJ-\\d{5,}.*");
        assertThat(lastMarkup()).containsEntry("remove_keyboard", true);

        Appeal a = appealsOf(tg).get(0);
        assertThat(a.getType()).isEqualTo(Appeal.Type.SUGGESTION);
        assertThat(a.getSource()).isEqualTo(Appeal.Source.BOT);
        assertThat(a.isAnonymous()).isFalse();
        assertThat(a.getContactName()).isEqualTo("Dilshod Aka");
        assertThat(a.getContactPhone()).isEqualTo(phone);
        assertThat(a.getAppIdentityId()).isNull();
        assertThat(a.getStatus()).isEqualTo(Appeal.Status.NEW);
        assertThat(lastText()).contains(a.getNumber());
        // Ulanmagan kontakt hisobni ulamaydi; holat tozalangan
        assertThat(identityRepository.findByTelegramUserId(tg)).isEmpty();
        assertThat(inTx(() -> conversationRepository.findById(tg))).isEmpty();

        // SA qo'ng'iroqchasi — yuboruvchi ma'lumotisiz
        assertThat(bells(sa)).singleElement().satisfies(n -> {
            assertThat(n.getType()).isEqualTo("APPEAL_NEW");
            assertThat(n.getTitle()).contains(a.getNumber());
            assertThat(n.getLink()).isEqualTo("/appeals/" + a.getId());
            assertThat(n.getBody()).doesNotContain("Dilshod");
        });

        // CRM: ismi bilan — yuboruvchi ko'rinadi
        mvc.perform(get("/api/appeals/{id}", a.getId()).with(as(sa))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.sender.name").value("Dilshod Aka"))
            .andExpect(jsonPath("$.data.sender.phone").value(phone))
            .andExpect(jsonPath("$.data.sender.linked").value(false));
    }

    // ── Bot: ulangan, anonim — anonimlik API'da sizmaydi ──────────────────

    @Test
    void bot_linkedAnonymous_noSenderStored_noLeakInApi() throws Exception {
        User sa = staff(UserRole.SUPER_ADMIN, null);
        String studentPhone = phone();
        student("Jasur", "Toshmatov", studentPhone, null);
        long tg = 8_902;
        shareContact(tg, studentPhone);
        botApi.reset();

        say(tg, "/taklif");
        press(tg, "apl:t:COMPLAINT");
        press(tg, "apl:a:1");
        assertThat(lastText()).contains("Murojaat matnini yozing");   // ism/telefon so'ralmaydi
        say(tg, "Xonada sovuq");
        assertThat(lastText()).contains("Anonim");
        press(tg, "apl:ok");

        Appeal a = appealsOf(tg).get(0);
        assertThat(a.isAnonymous()).isTrue();
        assertThat(a.getAppIdentityId()).isNull();
        assertThat(a.getStudentId()).isNull();
        assertThat(a.getParentId()).isNull();
        assertThat(a.getContactName()).isNull();
        assertThat(a.getContactPhone()).isNull();
        assertThat(a.getTelegramChatId()).isEqualTo(tg);              // faqat yetkazish kaliti

        String detail = mvc.perform(get("/api/appeals/{id}", a.getId()).with(as(sa))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.anonymous").value(true))
            .andExpect(jsonPath("$.data.sender").doesNotExist())
            .andReturn().getResponse().getContentAsString();
        String list = mvc.perform(get("/api/appeals").with(as(sa))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].senderName").doesNotExist())
            .andReturn().getResponse().getContentAsString();
        for (String body : List.of(detail, list)) {
            assertThat(body).doesNotContain(String.valueOf(tg)).doesNotContain("Jasur").doesNotContain("Toshmatov")
                .doesNotContain(studentPhone.substring(4)).doesNotContain("telegramChatId").doesNotContain("chatId");
        }
        // Qidiruv ham o'quvchi ismi bo'yicha topmaydi
        mvc.perform(get("/api/appeals").param("q", "Jasur").with(as(sa)))
            .andExpect(jsonPath("$.data.content", hasSize(0)));

        // Yuboruvchi Mini App'da o'zinikini ko'radi
        mvc.perform(get("/api/app/appeals").header("Authorization", bearer(appToken(tg))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items", hasSize(1)))
            .andExpect(jsonPath("$.data.items[0].number").value(a.getNumber()))
            .andExpect(jsonPath("$.data.items[0].anonymous").value(true));
    }

    @Test
    void bot_linkedNamed_identityAndStudentStored() throws Exception {
        User sa = staff(UserRole.SUPER_ADMIN, null);
        String studentPhone = phone();
        Long studentId = student("Malika", "Qodirova", studentPhone, null);
        long tg = 8_903;
        shareContact(tg, studentPhone);

        Appeal a = submitLinked(tg, "SERVICE", false, "Wi-Fi ishlamayapti");
        assertThat(a.getAppIdentityId()).isNotNull();
        assertThat(a.getStudentId()).isEqualTo(studentId);
        assertThat(a.getContactName()).isNull();

        mvc.perform(get("/api/appeals/{id}", a.getId()).with(as(sa))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.sender.linked").value(true))
            .andExpect(jsonPath("$.data.sender.kind").value("STUDENT"))
            .andExpect(jsonPath("$.data.sender.student.name").value("Malika Qodirova"))
            .andExpect(jsonPath("$.data.sender.phone").value(studentPhone));
    }

    // ── Bot: bekor qilish, eskirish, limit ───────────────────────────────

    @Test
    void bot_cancel_atEveryKindOfStep_andStaleButtons() throws Exception {
        long tg = 8_904;
        say(tg, "/taklif");
        press(tg, "apl:x");                                         // inline bekor
        assertThat(lastText()).contains("Bekor qilindi");
        assertThat(inTx(() -> conversationRepository.findById(tg))).isEmpty();

        press(tg, "apl:a:0");                                       // eskirgan tugma
        assertThat(lastText()).contains("eskirgan");

        say(tg, "/taklif");
        press(tg, "apl:t:OTHER");
        press(tg, "apl:a:1");
        say(tg, "❌ Bekor qilish");                                   // reply-klaviatura tugmasi
        assertThat(lastText()).contains("Bekor qilindi");
        assertThat(lastMarkup()).containsEntry("remove_keyboard", true);

        say(tg, "/taklif");
        press(tg, "apl:t:OTHER");
        press(tg, "apl:a:0");
        say(tg, "/cancel");                                          // ism qadamida buyruq
        assertThat(lastText()).contains("Bekor qilindi");

        say(tg, "/taklif");
        press(tg, "apl:t:OTHER");
        say(tg, "/start");                                           // /start oqimni tashlaydi
        assertThat(inTx(() -> conversationRepository.findById(tg))).isEmpty();

        assertThat(appealsOf(tg)).isEmpty();
    }

    @Test
    void bot_stateExpiresAfter30Minutes() throws Exception {
        long tg = 8_905;
        clock.setDateTime(LocalDateTime.of(2026, 9, 15, 10, 0));
        say(tg, "/taklif");
        press(tg, "apl:t:OTHER");
        press(tg, "apl:a:1");
        clock.setDateTime(LocalDateTime.of(2026, 9, 15, 10, 31));
        botApi.reset();
        say(tg, "Bu matn murojaat bo'lmasligi kerak");
        assertThat(botApi.sent.get(0).html()).contains("xush kelibsiz");  // oddiy /start javobi
        assertThat(appealsOf(tg)).isEmpty();
        assertThat(inTx(() -> conversationRepository.findById(tg))).isEmpty();
    }

    @Test
    void limit_fivePerChatPerDay_botAndMiniApp() throws Exception {
        String studentPhone = phone();
        student("Limit", "Test", studentPhone, null);
        long tg = 8_906;
        shareContact(tg, studentPhone);
        String token = appToken(tg);
        clock.setDateTime(LocalDateTime.of(2026, 9, 15, 9, 0));

        for (int i = 0; i < 4; i++) {
            mvc.perform(post("/api/app/appeals").header("Authorization", bearer(token))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"OTHER\",\"text\":\"Matn " + i + "\"}"))
                .andExpect(status().isOk());
        }
        submitLinked(tg, "OTHER", false, "Beshinchi");               // botdan — 5-chi
        assertThat(appealsOf(tg)).hasSize(5);

        mvc.perform(post("/api/app/appeals").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"OTHER\",\"text\":\"Oltinchi\"}"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("appeal.limit"));
        botApi.reset();
        say(tg, "/taklif");
        assertThat(lastText()).contains("kunlik chegara");
        mvc.perform(get("/api/app/appeals").header("Authorization", bearer(token)))
            .andExpect(jsonPath("$.data.remainingToday").value(0))
            .andExpect(jsonPath("$.data.dailyLimit").value(5));

        clock.setDateTime(LocalDateTime.of(2026, 9, 16, 9, 0));     // ertasi kun
        mvc.perform(post("/api/app/appeals").header("Authorization", bearer(appToken(tg)))
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"OTHER\",\"text\":\"Yangi kun\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void bot_textTooLong_rejectedAndStepKept() throws Exception {
        long tg = 8_907;
        say(tg, "/taklif");
        press(tg, "apl:t:OTHER");
        press(tg, "apl:a:1");
        say(tg, "a".repeat(3001));
        assertThat(lastText()).contains("juda uzun").contains("3001");
        say(tg, "Qisqa matn");
        assertThat(lastText()).contains("Tekshiring");
    }

    // ── Xodim javobi → outbox; reply → SENDER xabari ─────────────────────

    @Test
    void staffReply_queuedToOutboxWithReplyButton_internalNotSent() throws Exception {
        User sa = staff(UserRole.SUPER_ADMIN, null);
        long tg = 8_908;
        say(tg, "/taklif");
        press(tg, "apl:t:PAYMENT");
        press(tg, "apl:a:1");
        say(tg, "Chek kelmadi");
        press(tg, "apl:ok");
        Appeal a = appealsOf(tg).get(0);

        mvc.perform(post("/api/appeals/{id}/messages", a.getId()).with(as(sa))
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Ichki: kassirdan so'rang\",\"internal\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.internal").value(true))
            .andExpect(jsonPath("$.data.delivered").value(false));
        assertThat(outboxFor(tg)).isEmpty();

        mvc.perform(post("/api/appeals/{id}/messages", a.getId()).with(as(sa))
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Chek SMS orqali yuborildi\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.authorType").value("STAFF"))
            .andExpect(jsonPath("$.data.delivered").value(true));

        List<TelegramOutbox> out = outboxFor(tg);
        assertThat(out).singleElement().satisfies(o -> {
            assertThat(o.getText()).contains("№" + a.getNumber()).contains("Chek SMS orqali yuborildi")
                .doesNotContain("Ichki");
            assertThat(o.getReplyMarkup()).contains("apl:r:" + a.getId());
            assertThat(o.getEventCode()).isEqualTo("APPEAL_REPLY");
        });
        Appeal after = inTx(() -> appealRepository.findById(a.getId()).orElseThrow());
        assertThat(after.getStatus()).isEqualTo(Appeal.Status.IN_PROGRESS);
        assertThat(after.getFirstResponseAt()).isNotNull();

        // Mini App'da ichki izoh ko'rinmaydi
        mvc.perform(get("/api/app/appeals").header("Authorization", bearer(appTokenFor(tg))))
            .andExpect(jsonPath("$.data.items[0].messages", hasSize(1)))
            .andExpect(jsonPath("$.data.items[0].messages[0].authorType").value("STAFF"));
    }

    /** Anonim, ulanmagan chat uchun Mini App tokeni yo'q — bu holatda o'quvchi raqamini ulab olamiz. */
    private String appTokenFor(long tg) throws Exception {
        String studentPhone = phone();
        student("Token", "Uchun", studentPhone, null);
        shareContact(tg, studentPhone);
        return appToken(tg);
    }

    @Test
    void senderReply_viaTelegramReplyAndButton_reopensClosedAppeal() throws Exception {
        User sa = staff(UserRole.SUPER_ADMIN, null);
        long tg = 8_909;
        long stranger = 8_910;
        say(tg, "/taklif");
        press(tg, "apl:t:SUGGESTION");
        press(tg, "apl:a:1");
        say(tg, "Kutubxona kerak");
        press(tg, "apl:ok");
        Appeal a = appealsOf(tg).get(0);
        mvc.perform(post("/api/appeals/{id}/messages", a.getId()).with(as(sa))
            .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Rahmat, ko'rib chiqamiz\"}"));
        mvc.perform(patch("/api/appeals/{id}", a.getId()).with(as(sa))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RESOLVED\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.resolvedAt").exists());
        String staffReply = outboxFor(tg).get(0).getText();
        assertThat(outboxFor(tg)).hasSize(2);                          // javob + "hal qilindi"
        int bellsBefore = bells(sa).size();

        // Begona chat shu matnga reply qilsa — murojaatga qo'shilmaydi
        replyTo(stranger, staffReply, "Men ham qo'shilaman");
        assertThat(inTx(() -> messageRepository.findByAppealIdOrderByIdAsc(a.getId()))).hasSize(1);

        // Telegram reply — SENDER xabari, yopilgan murojaat qayta ochiladi
        botApi.reset();
        replyTo(tg, staffReply, "Qachon bo'ladi?");
        assertThat(lastText()).contains("qo'shildi").contains(a.getNumber());
        List<AppealMessage> ms = inTx(() -> messageRepository.findByAppealIdOrderByIdAsc(a.getId()));
        assertThat(ms).hasSize(2);
        assertThat(ms.get(1).getAuthorType()).isEqualTo(AppealMessage.AuthorType.SENDER);
        assertThat(ms.get(1).getText()).isEqualTo("Qachon bo'ladi?");
        Appeal reopened = inTx(() -> appealRepository.findById(a.getId()).orElseThrow());
        assertThat(reopened.getStatus()).isEqualTo(Appeal.Status.IN_PROGRESS);
        assertThat(reopened.getResolvedAt()).isNull();
        assertThat(bells(sa)).hasSize(bellsBefore + 1)
            .first().satisfies(n -> assertThat(n.getType()).isEqualTo("APPEAL_MESSAGE"));

        // "Javob yozish" tugmasi
        press(tg, "apl:r:" + a.getId());
        assertThat(lastText()).contains("javobingizni yozing");
        say(tg, "Yana bir savol");
        assertThat(lastText()).contains("qo'shildi");
        assertThat(inTx(() -> messageRepository.findByAppealIdOrderByIdAsc(a.getId()))).hasSize(3);

        // Begona murojaatning tugmasi — topilmadi
        press(stranger, "apl:r:" + a.getId());
        RecordingTelegramBotApi.Sent last = botApi.sent.get(botApi.sent.size() - 1);
        assertThat(last.chatId()).isEqualTo(stranger);
        assertThat(last.html()).contains("topilmadi");
    }

    // ── Ruxsatlar ────────────────────────────────────────────────────────

    @Test
    void permissions_adminOnlyAssigned_teacherAndOthers403() throws Exception {
        User sa = staff(UserRole.SUPER_ADMIN, null);
        User admin = staff(UserRole.ADMIN, null);
        User otherAdmin = staff(UserRole.ADMIN, null);
        Appeal a = appeal(9_001, Appeal.Type.OTHER, false, null);

        for (UserRole role : List.of(UserRole.TEACHER, UserRole.ACCOUNTANT, UserRole.SALES_MANAGER, UserRole.SALES_HEAD)) {
            User u = staff(role, null);
            mvc.perform(get("/api/appeals").with(as(u))).andExpect(status().isForbidden());
            mvc.perform(get("/api/appeals/{id}", a.getId()).with(as(u))).andExpect(status().isForbidden());
            mvc.perform(get("/api/appeals/counts").with(as(u))).andExpect(status().isForbidden());
        }

        // ADMIN — biriktirilmagan: 404, ro'yxatda yo'q
        mvc.perform(get("/api/appeals/{id}", a.getId()).with(as(admin)))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("appeal.notFound"));
        mvc.perform(get("/api/appeals").with(as(admin))).andExpect(jsonPath("$.data.content", hasSize(0)));
        mvc.perform(get("/api/appeals/counts").with(as(admin))).andExpect(jsonPath("$.data.new").value(0));
        mvc.perform(get("/api/appeals/counts").with(as(sa))).andExpect(jsonPath("$.data.new").value(1));

        // SA biriktiradi → ADMIN ko'radi, qo'ng'iroqcha oladi
        mvc.perform(patch("/api/appeals/{id}", a.getId()).with(as(sa)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"assigneeUserId\":" + admin.getId() + "}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.assignee.id").value(admin.getId()));
        assertThat(bells(admin)).singleElement().satisfies(n -> assertThat(n.getTitle()).contains("biriktirildi"));
        mvc.perform(get("/api/appeals/{id}", a.getId()).with(as(admin))).andExpect(status().isOk());
        mvc.perform(get("/api/appeals").with(as(admin))).andExpect(jsonPath("$.data.content", hasSize(1)));
        mvc.perform(get("/api/appeals/counts").with(as(admin))).andExpect(jsonPath("$.data.new").value(1));
        mvc.perform(get("/api/appeals/{id}", a.getId()).with(as(otherAdmin))).andExpect(status().isNotFound());

        // ADMIN holatni o'zgartira oladi, mas'ulni — yo'q
        mvc.perform(patch("/api/appeals/{id}", a.getId()).with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"IN_PROGRESS\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
        mvc.perform(patch("/api/appeals/{id}", a.getId()).with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"assigneeUserId\":" + otherAdmin.getId() + "}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("appeal.assign.forbidden"));
        mvc.perform(patch("/api/appeals/{id}", a.getId()).with(as(sa)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"NEW\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("appeal.status.invalidTransition"));

        // Faqat SA/ADMIN mas'ul bo'la oladi; null — mas'ulni olib tashlash
        User teacher = staff(UserRole.TEACHER, null);
        mvc.perform(patch("/api/appeals/{id}", a.getId()).with(as(sa)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"assigneeUserId\":" + teacher.getId() + "}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("appeal.assignee.invalid"));
        mvc.perform(patch("/api/appeals/{id}", a.getId()).with(as(sa)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"assigneeUserId\":null}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.assignee").doesNotExist());
        mvc.perform(get("/api/appeals/{id}", a.getId()).with(as(admin))).andExpect(status().isNotFound());
    }

    @Test
    void teacherTypeAppeal_neverVisibleToThatTeacher_evenAsAdminOrAssignee() throws Exception {
        User sa = staff(UserRole.SUPER_ADMIN, null);
        User teacherAdmin = staff(UserRole.ADMIN, null);              // o'qituvchi ham, admin ham
        Long teacherId = teacherProfile(teacherAdmin);
        User teacherSa = staff(UserRole.SUPER_ADMIN, null);           // SA ham o'qituvchi bo'lishi mumkin
        Long teacher2 = inTx(() -> {
            com.crm.entity.Teacher t = teacherRepository.findById(fixtures.teacher()).orElseThrow();
            t.setUser(userRepository.findById(teacherSa.getId()).orElseThrow());
            return teacherRepository.save(t).getId();
        });

        Appeal aboutAdmin = appeal(9_101, Appeal.Type.TEACHER, true, teacherId);
        Appeal aboutSa = appeal(9_102, Appeal.Type.TEACHER, true, teacher2);

        // Unga biriktirib bo'lmaydi
        mvc.perform(patch("/api/appeals/{id}", aboutAdmin.getId()).with(as(sa)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"assigneeUserId\":" + teacherAdmin.getId() + "}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("appeal.assignee.isSubject"));

        // SA-o'qituvchi o'zi haqidagini ko'rmaydi (boshqasini ko'radi)
        mvc.perform(get("/api/appeals/{id}", aboutSa.getId()).with(as(teacherSa))).andExpect(status().isNotFound());
        mvc.perform(get("/api/appeals/{id}", aboutAdmin.getId()).with(as(teacherSa))).andExpect(status().isOk());
        String list = mvc.perform(get("/api/appeals").with(as(teacherSa))).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        List<Integer> ids = JsonPath.read(list, "$.data.content[*].id");
        assertThat(ids).containsExactly(aboutAdmin.getId().intValue());
        mvc.perform(get("/api/appeals/stats").with(as(teacherSa))).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/api/appeals/stats").with(as(sa))).andExpect(jsonPath("$.data.total").value(2));

        // Avval biriktirilgan, keyin o'qituvchi sifatida ko'rsatilgan admin ham ko'rmaydi
        Appeal later = appeal(9_103, Appeal.Type.TEACHER, false, null);
        mvc.perform(patch("/api/appeals/{id}", later.getId()).with(as(sa)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"assigneeUserId\":" + teacherAdmin.getId() + "}")).andExpect(status().isOk());
        mvc.perform(get("/api/appeals/{id}", later.getId()).with(as(teacherAdmin))).andExpect(status().isOk());
        mvc.perform(patch("/api/appeals/{id}", later.getId()).with(as(sa)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"teacherId\":" + teacherId + "}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("appeal.assignee.isSubject"));
        inTx(() -> {
            Appeal x = appealRepository.findById(later.getId()).orElseThrow();
            x.setTeacherId(teacherId);
        });
        mvc.perform(get("/api/appeals/{id}", later.getId()).with(as(teacherAdmin))).andExpect(status().isNotFound());
        mvc.perform(get("/api/appeals").with(as(teacherAdmin))).andExpect(jsonPath("$.data.content", hasSize(0)));

        // teacherId faqat TEACHER turida
        Appeal other = appeal(9_104, Appeal.Type.OTHER, false, null);
        mvc.perform(patch("/api/appeals/{id}", other.getId()).with(as(sa)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"teacherId\":" + teacherId + "}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("appeal.teacher.typeMismatch"));
    }

    @Test
    void teacherTypeAppeal_newBellNotSentToSubjectTeacher() throws Exception {
        User teacherSa = staff(UserRole.SUPER_ADMIN, null);
        User plainSa = staff(UserRole.SUPER_ADMIN, null);
        Long teacherId = teacherProfile(teacherSa);
        String parentPhone = phone();
        Long studentId = student("Bola", "Ota", phone(), parentPhone);
        Long group = fixtures.group(fixtures.course(500_000), GroupStatus.ACTIVE, teacherId);
        fixtures.enrollment(studentId, group).start(d("01.09.2026")).save();
        long tg = 8_911;
        shareContact(tg, parentPhone);
        String token = appToken(tg);

        // Mini App: o'qituvchi tanlovi — farzandining o'qituvchisi
        mvc.perform(get("/api/app/appeals").header("Authorization", bearer(token)))
            .andExpect(jsonPath("$.data.teachers[0].id").value(teacherId))
            .andExpect(jsonPath("$.data.enabled").value(true));
        Long strangerTeacher = fixtures.teacher();
        mvc.perform(post("/api/app/appeals").header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"TEACHER\",\"text\":\"x\",\"teacherId\":" + strangerTeacher + "}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("appeal.teacher.notFound"));
        mvc.perform(post("/api/app/appeals").header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"OTHER\",\"text\":\"x\",\"teacherId\":" + teacherId + "}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("appeal.teacher.typeMismatch"));
        mvc.perform(post("/api/app/appeals").header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"TEACHER\",\"text\":\"Uy vazifasi juda ko'p\",\"teacherId\":" + teacherId
                    + ",\"studentId\":" + studentId + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.teacher.id").value(teacherId))
            .andExpect(jsonPath("$.data.status").value("NEW"));

        Appeal a = appealsOf(tg).get(0);
        assertThat(a.getSource()).isEqualTo(Appeal.Source.MINIAPP);
        assertThat(a.getStudentId()).isEqualTo(studentId);
        assertThat(a.getParentId()).isNull();                         // ota-ona yozuvi yo'q (students.parent_phone)
        assertThat(bells(plainSa)).singleElement().satisfies(n -> assertThat(n.getType()).isEqualTo("APPEAL_NEW"));
        assertThat(bells(teacherSa)).isEmpty();
    }

    // ── Mini App ─────────────────────────────────────────────────────────

    @Test
    void miniApp_messages_ownOnly_andValidation() throws Exception {
        User sa = staff(UserRole.SUPER_ADMIN, null);
        String p1 = phone();
        String p2 = phone();
        student("Bir", "Inchi", p1, null);
        student("Ikki", "Nchi", p2, null);
        shareContact(8_912, p1);
        shareContact(8_913, p2);
        String mine = appToken(8_912);
        String other = appToken(8_913);

        mvc.perform(post("/api/app/appeals").header("Authorization", bearer(mine)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"OTHER\",\"text\":\"   \"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("appeal.text.required"));
        mvc.perform(post("/api/app/appeals").header("Authorization", bearer(mine)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"NOPE\",\"text\":\"a\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("appeal.type.invalid"));
        mvc.perform(post("/api/app/appeals").header("Authorization", bearer(mine)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"OTHER\",\"text\":\"" + "b".repeat(3001) + "\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("appeal.text.tooLong"));
        String body = mvc.perform(post("/api/app/appeals").header("Authorization", bearer(mine))
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"SUGGESTION\",\"text\":\"Bayram tadbiri\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.number").exists())
            .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(body, "$.data.id")).longValue();

        mvc.perform(post("/api/app/appeals/{id}/messages", id).header("Authorization", bearer(other))
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Begona\"}"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("appeal.notFound"));
        mvc.perform(get("/api/app/appeals").header("Authorization", bearer(other)))
            .andExpect(jsonPath("$.data.items", hasSize(0)));

        mvc.perform(post("/api/app/appeals/{id}/messages", id).header("Authorization", bearer(mine))
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Qo'shimcha\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.messages", hasSize(1)))
            .andExpect(jsonPath("$.data.messages[0].authorType").value("SENDER"));
        assertThat(bells(sa)).extracting(UserNotification::getType).containsExactly("APPEAL_MESSAGE", "APPEAL_NEW");

        // Tokensiz Mini App — 401; app JWT CRM'ga kira olmaydi
        mvc.perform(get("/api/app/appeals")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/appeals").header("Authorization", bearer(mine))).andExpect(status().isUnauthorized());
    }

    @Test
    void stats_countsAndAverages() throws Exception {
        User sa = staff(UserRole.SUPER_ADMIN, null);
        clock.setDateTime(LocalDateTime.of(2026, 9, 10, 10, 0));
        Appeal a = appeal(9_201, Appeal.Type.COMPLAINT, true, null);
        Appeal b = appeal(9_202, Appeal.Type.SUGGESTION, false, null);
        clock.setDateTime(LocalDateTime.of(2026, 9, 10, 11, 0));      // 60 daqiqada javob
        mvc.perform(post("/api/appeals/{id}/messages", a.getId()).with(as(sa))
            .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Javob\"}")).andExpect(status().isOk());
        clock.setDateTime(LocalDateTime.of(2026, 9, 10, 12, 0));      // 120 daqiqada yopildi
        mvc.perform(patch("/api/appeals/{id}", a.getId()).with(as(sa)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"REJECTED\"}")).andExpect(status().isOk());
        assertThat(b.getId()).isNotNull();

        mvc.perform(get("/api/appeals/stats").param("from", "2026-09-01").param("to", "2026-09-30").with(as(sa)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(2))
            .andExpect(jsonPath("$.data.byType.COMPLAINT").value(1))
            .andExpect(jsonPath("$.data.byType.SUGGESTION").value(1))
            .andExpect(jsonPath("$.data.byType.PAYMENT").value(0))
            .andExpect(jsonPath("$.data.byStatus.REJECTED").value(1))
            .andExpect(jsonPath("$.data.byStatus.NEW").value(1))
            .andExpect(jsonPath("$.data.avgFirstResponseMinutes").value(60))
            .andExpect(jsonPath("$.data.avgResolutionMinutes").value(120));
        mvc.perform(get("/api/appeals/stats").param("from", "2026-10-01").param("to", "2026-10-31").with(as(sa)))
            .andExpect(jsonPath("$.data.total").value(0))
            .andExpect(jsonPath("$.data.avgFirstResponseMinutes").doesNotExist());
        mvc.perform(get("/api/appeals").param("status", "REJECTED").with(as(sa)))
            .andExpect(jsonPath("$.data.content", hasSize(1)))
            .andExpect(jsonPath("$.data.content[0].type").value("COMPLAINT"));
        mvc.perform(get("/api/appeals").param("type", "SUGGESTION").param("q", b.getNumber().substring(4)).with(as(sa)))
            .andExpect(jsonPath("$.data.content", hasSize(1)));
    }
}
