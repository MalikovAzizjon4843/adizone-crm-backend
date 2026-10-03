package com.crm.miniapp;

import com.crm.billing.support.RecordingTelegramBotApi;
import com.crm.entity.AppIdentity;
import com.crm.entity.AppIdentityStudent;
import com.crm.entity.enums.StudentStatus;
import com.crm.entity.enums.UserRole;
import com.crm.repository.AppLinkAttemptRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bot webhook (docs/design/telegram-platform.md §1, §3.4, §8 "Webhook", "Telefon moslash").
 */
class TelegramBotWebhookTest extends MiniAppItBase {

    @Autowired
    private AppLinkAttemptRepository attemptRepository;

    // ── Secret sarlavha ──────────────────────────────────────────────────

    @Test
    void webhook_wrongOrMissingSecret_401() throws Exception {
        String update = textUpdate(nextUpdateId(), 501, "/start");
        mvc.perform(post("/api/telegram/webhook").contentType(MediaType.APPLICATION_JSON).content(update))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/telegram/webhook").header("X-Telegram-Bot-Api-Secret-Token", "wrong")
                .contentType(MediaType.APPLICATION_JSON).content(update))
            .andExpect(status().isUnauthorized());
        assertThat(botApi.sent).isEmpty();
    }

    // ── /start ───────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void start_sendsOpenAppAndContactButtons() throws Exception {
        webhook(textUpdate(nextUpdateId(), 502, "/start")).andExpect(status().isOk());

        assertThat(botApi.sent).hasSize(2);
        RecordingTelegramBotApi.Sent first = botApi.sent.get(0);
        assertThat(first.chatId()).isEqualTo(502);
        List<List<Map<String, Object>>> inline = (List<List<Map<String, Object>>>) first.replyMarkup().get("inline_keyboard");
        assertThat(inline.get(0).get(0)).containsEntry("text", "Ilovani ochish")
            .containsEntry("web_app", Map.of("url", "https://webapp.example.test"));

        List<List<Map<String, Object>>> keyboard = (List<List<Map<String, Object>>>) botApi.sent.get(1).replyMarkup().get("keyboard");
        assertThat(keyboard.get(0).get(0)).containsEntry("text", "Telefon raqamni yuborish")
            .containsEntry("request_contact", true);
    }

    @Test
    void duplicateUpdateId_handledOnce() throws Exception {
        long id = nextUpdateId();
        webhook(textUpdate(id, 503, "/start")).andExpect(status().isOk());
        webhook(textUpdate(id, 503, "/start")).andExpect(status().isOk());
        assertThat(botApi.sent).hasSize(2);
    }

    @Test
    void groupChat_ignored() throws Exception {
        String update = "{\"update_id\":" + nextUpdateId() + ",\"message\":{\"message_id\":1,"
            + "\"from\":{\"id\":504,\"is_bot\":false,\"first_name\":\"A\"},"
            + "\"chat\":{\"id\":-100123,\"type\":\"supergroup\"},\"date\":1,\"text\":\"/start\"}}";
        webhook(update).andExpect(status().isOk());
        assertThat(botApi.sent).isEmpty();
    }

    // ── Kontakt ──────────────────────────────────────────────────────────

    @Test
    void contactOfAnotherUser_rejected() throws Exception {
        String phone = phone();
        student("Dilnoza", "Karimova", phone, null);

        webhook(contactUpdate(nextUpdateId(), 505, 999_999L, phone.substring(1))).andExpect(status().isOk());
        webhook(contactUpdate(nextUpdateId(), 505, null, phone.substring(1))).andExpect(status().isOk());

        assertThat(identityRepository.findByTelegramUserId(505L)).isEmpty();
        assertThat(botApi.sent).hasSize(2).allSatisfy(s -> assertThat(s.html()).contains("o'zingizning raqamingizni"));
    }

    @Test
    void contact_studentPhone_linksAsStudent() throws Exception {
        String phone = phone();
        Long studentId = student("Dilnoza", "Karimova", phone, null);

        // Telegram kontaktida "+" yo'q — PhoneUtils.canonical
        webhook(contactUpdate(nextUpdateId(), 506, 506L, phone.substring(1))).andExpect(status().isOk());

        AppIdentity identity = identityRepository.findByTelegramUserId(506L).orElseThrow();
        assertThat(identity.getKind()).isEqualTo(AppIdentity.Kind.STUDENT);
        assertThat(identity.getPhoneCanonical()).isEqualTo(phone);
        assertThat(identity.getChatId()).isEqualTo(506L);
        assertThat(identityStudentRepository.findByIdentityIdOrderByIdAsc(identity.getId()))
            .extracting(AppIdentityStudent::getStudentId, AppIdentityStudent::getRelation)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(studentId, AppIdentityStudent.Relation.SELF));
        assertThat(botApi.sent).hasSize(1);
        assertThat(botApi.sent.get(0).html()).contains("Hisob ulandi").contains("Dilnoza Karimova");
        assertThat(botApi.sent.get(0).replyMarkup()).containsKey("inline_keyboard");
    }

    @Test
    void contact_parentRecord_linksAllChildren_exceptArchived() throws Exception {
        String parentPhone = phone();
        Long a = student("Ali", "Karimov", phone(), null);
        Long b = student("Vali", "Karimov", phone(), null);
        Long archived = student("Olim", "Karimov", phone(), null);
        inTx(() -> studentRepository.findById(archived).orElseThrow().setStatus(StudentStatus.ARCHIVED));
        parentOf(parentPhone, a, b, archived);

        shareContact(507, parentPhone);

        AppIdentity identity = identityRepository.findByTelegramUserId(507L).orElseThrow();
        assertThat(identity.getKind()).isEqualTo(AppIdentity.Kind.PARENT);
        assertThat(identityStudentRepository.findByIdentityIdOrderByIdAsc(identity.getId()))
            .extracting(AppIdentityStudent::getStudentId).containsExactlyInAnyOrder(a, b);
    }

    @Test
    void contact_samePhoneOnTwoStudents_parentWithSwitcher() throws Exception {
        String shared = phone();
        Long a = student("Ali", "Karimov", shared, null);
        Long b = student("Vali", "Karimov", shared, null);

        shareContact(508, shared);

        AppIdentity identity = identityRepository.findByTelegramUserId(508L).orElseThrow();
        assertThat(identity.getKind()).isEqualTo(AppIdentity.Kind.PARENT);
        assertThat(identityStudentRepository.findByIdentityIdOrderByIdAsc(identity.getId()))
            .extracting(AppIdentityStudent::getStudentId, AppIdentityStudent::getRelation)
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple(a, AppIdentityStudent.Relation.PARENT),
                org.assertj.core.groups.Tuple.tuple(b, AppIdentityStudent.Relation.PARENT));
    }

    @Test
    void contact_studentAndParentOfOthers_allLinked() throws Exception {
        String p = phone();
        Long self = student("Madina", "Aliyeva", p, null);
        Long child = student("Kamola", "Aliyeva", phone(), p);   // legacy students.parent_phone

        shareContact(509, p);

        AppIdentity identity = identityRepository.findByTelegramUserId(509L).orElseThrow();
        assertThat(identity.getKind()).isEqualTo(AppIdentity.Kind.PARENT);
        assertThat(identityStudentRepository.findByIdentityIdOrderByIdAsc(identity.getId()))
            .extracting(AppIdentityStudent::getStudentId, AppIdentityStudent::getRelation)
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple(self, AppIdentityStudent.Relation.SELF),
                org.assertj.core.groups.Tuple.tuple(child, AppIdentityStudent.Relation.PARENT));
    }

    @Test
    void contact_notFound_thenLimitedAfterFive() throws Exception {
        for (int i = 0; i < 5; i++) {
            shareContact(510, phone());
        }
        assertThat(botApi.sent).hasSize(5).allSatisfy(s -> assertThat(s.html()).contains("topilmadi")
            .contains("+998 77 337 32 33"));

        // 6-urinish — mos raqam bo'lsa ham 24 soat blok
        String real = phone();
        student("Ali", "Valiyev", real, null);
        shareContact(510, real);
        assertThat(botApi.sent.get(5).html()).contains("Urinishlar soni oshib ketdi");
        assertThat(identityRepository.findByTelegramUserId(510L)).isEmpty();
        assertThat(attemptRepository.count()).isEqualTo(6);
    }

    @Test
    void stop_unlinksAndBumpsVersion() throws Exception {
        String phone = phone();
        student("Ali", "Valiyev", phone, null);
        shareContact(511, phone);
        int version = identityRepository.findByTelegramUserId(511L).orElseThrow().getIdentityVersion();

        webhook(textUpdate(nextUpdateId(), 511, "/stop")).andExpect(status().isOk());

        AppIdentity identity = identityRepository.findByTelegramUserId(511L).orElseThrow();
        assertThat(identity.getStatus()).isEqualTo(AppIdentity.Status.UNLINKED);
        assertThat(identity.getIdentityVersion()).isEqualTo(version + 1);
        assertThat(identityStudentRepository.findByIdentityIdOrderByIdAsc(identity.getId())).isEmpty();
    }

    // ── SA: set-webhook ──────────────────────────────────────────────────

    @Test
    void setWebhook_superAdmin_passesUrlAndSecret() throws Exception {
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        mvc.perform(post("/api/admin/telegram/set-webhook").with(user("test-super_admin").roles("SUPER_ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.url").value("https://api.example.test/api/telegram/webhook"))
            .andExpect(jsonPath("$.data.secret").doesNotExist());

        assertThat(botApi.webhookCalls).singleElement().satisfies(c -> {
            assertThat(c.url()).isEqualTo("https://api.example.test/api/telegram/webhook");
            assertThat(c.secretToken()).isEqualTo(WEBHOOK_SECRET);
            assertThat(c.allowedUpdates()).containsExactly("message");
        });
    }

    @Test
    void setWebhook_telegramError_502() throws Exception {
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        botApi.webhookOk = false;
        mvc.perform(post("/api/admin/telegram/set-webhook").with(user("test-super_admin").roles("SUPER_ADMIN")))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.code").value("telegram.setWebhook.failed"));
    }

    @Test
    void setWebhook_admin_forbidden() throws Exception {
        mvc.perform(post("/api/admin/telegram/set-webhook").with(user("a").roles("ADMIN")))
            .andExpect(status().isForbidden());
        assertThat(botApi.webhookCalls).isEmpty();
    }
}
