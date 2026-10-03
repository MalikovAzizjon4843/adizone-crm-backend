package com.crm.miniapp;

import com.crm.entity.AppIdentity;
import com.crm.entity.AppLinkRequest;
import com.crm.entity.TelegramOutbox;
import com.crm.entity.User;
import com.crm.entity.enums.UserRole;
import com.crm.repository.AppLinkRequestRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Qo'lda raqam bilan ulash (docs/design/telegram-platform.md §11.1). */
class MiniAppLinkRequestTest extends MiniAppItBase {

    @Autowired
    private AppLinkRequestRepository requestRepository;

    private ResultActions manual(long telegramUserId, String phone) throws Exception {
        return mvc.perform(post("/api/app/link/manual").contentType(MediaType.APPLICATION_JSON)
            .content("{\"initData\":" + json(initData(telegramUserId)) + ",\"phone\":" + json(phone) + "}"));
    }

    private Long submit(long telegramUserId, String phone) throws Exception {
        String body = manual(telegramUserId, phone).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PENDING"))
            .andExpect(jsonPath("$.message").value("So'rov yuborildi, markaz tasdiqlaydi"))
            .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.data.requestId")).longValue();
    }

    @Test
    void unknownPhone_404_andDailyLimit() throws Exception {
        for (int i = 0; i < 5; i++) {
            manual(901, phone()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("app.phoneNotFound"));
        }
        String real = phone();
        student("Ali", "Karimov", real, null);
        manual(901, real).andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("app.link.rateLimited"));
        // Noto'g'ri formatdagi raqam ham "topilmadi"
        manual(902, "12345").andExpect(status().isNotFound());
    }

    @Test
    void invalidInitData_401_andAlreadyLinked_409() throws Exception {
        mvc.perform(post("/api/app/link/manual").contentType(MediaType.APPLICATION_JSON)
                .content("{\"initData\":\"user=%7B%7D&hash=00\",\"phone\":\"+998901112233\"}"))
            .andExpect(status().isUnauthorized());

        String phone = phone();
        student("Ali", "Karimov", phone, null);
        shareContact(903, phone);
        manual(903, phone).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("app.link.alreadyLinked"));
    }

    @Test
    void submit_approve_linksIdentity_andBotMessage() throws Exception {
        String phone = phone();
        Long studentId = student("Dilnoza", "Karimova", phone(), phone);   // ota-ona raqami
        Long requestId = submit(904, phone.substring(4));                  // 9 xonali — kanonik shaklga keltiriladi
        // Shu raqam bilan takror — o'sha so'rov
        assertThat(submit(904, phone)).isEqualTo(requestId);

        User admin = staff(UserRole.ADMIN, null);
        mvc.perform(get("/api/app-link-requests").with(as(admin))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data", hasSize(1)))
            .andExpect(jsonPath("$.data[0].phone").value(phone))
            .andExpect(jsonPath("$.data[0].matchSummary").value("Farzand: Dilnoza Karimova"));
        // Mini App hali ulanmagan
        auth(initData(904)).andExpect(status().isForbidden());

        mvc.perform(post("/api/app-link-requests/{id}/approve", requestId).with(as(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("APPROVED"))
            .andExpect(jsonPath("$.data.decidedByName").isString());

        AppIdentity identity = identityRepository.findByTelegramUserId(904L).orElseThrow();
        assertThat(identity.getKind()).isEqualTo(AppIdentity.Kind.PARENT);
        auth(initData(904)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.profile.students[0].id").value(studentId));

        // Bot xabari outbox orqali (12:00 — sokin soat emas)
        assertThat(outboxFor(904)).singleElement().satisfies(o -> {
            assertThat(o.getText()).contains("tasdiqlandi").contains("Dilnoza Karimova");
            assertThat(o.getStatus()).isEqualTo(TelegramOutbox.Status.PENDING);
        });
        assertThat(outboxWorker.runOnce()).isEqualTo(1);
        assertThat(botApi.sent).singleElement().satisfies(s -> assertThat(s.chatId()).isEqualTo(904));

        // Qayta tasdiqlab bo'lmaydi
        mvc.perform(post("/api/app-link-requests/{id}/approve", requestId).with(as(admin)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("appLinkRequest.notPending"));
    }

    @Test
    void newPhone_cancelsPreviousPending() throws Exception {
        String a = phone();
        String b = phone();
        student("Ali", "Karimov", a, null);
        student("Vali", "Karimov", b, null);
        Long first = submit(905, a);
        Long second = submit(905, b);
        assertThat(second).isNotEqualTo(first);
        assertThat(requestRepository.findById(first).orElseThrow().getStatus()).isEqualTo(AppLinkRequest.Status.CANCELLED);
    }

    @Test
    void reject_requiresReason_andNotifies() throws Exception {
        String phone = phone();
        student("Ali", "Karimov", phone, null);
        Long requestId = submit(906, phone);
        User head = staff(UserRole.SALES_HEAD, null);

        mvc.perform(post("/api/app-link-requests/{id}/reject", requestId).with(as(head))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"  \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("appLinkRequest.reason.required"));
        mvc.perform(post("/api/app-link-requests/{id}/reject", requestId).with(as(head))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Raqam <boshqa> odamniki\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("REJECTED"))
            .andExpect(jsonPath("$.data.rejectReason").value("Raqam <boshqa> odamniki"));

        assertThat(identityRepository.findByTelegramUserId(906L)).isEmpty();
        assertThat(outboxFor(906)).singleElement()
            .satisfies(o -> assertThat(o.getText()).contains("rad etildi").contains("&lt;boshqa&gt;"));
        mvc.perform(get("/api/app-link-requests").param("status", "REJECTED").with(as(head)))
            .andExpect(jsonPath("$.data", hasSize(1)));
    }

    @Test
    void crmEndpoints_roles() throws Exception {
        for (UserRole role : new UserRole[]{UserRole.SALES_MANAGER, UserRole.TEACHER, UserRole.ACCOUNTANT}) {
            mvc.perform(get("/api/app-link-requests").with(as(staff(role, null)))).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/app-link-requests").with(as(staff(UserRole.SUPER_ADMIN, null)))).andExpect(status().isOk());
        mvc.perform(get("/api/app-link-requests").param("status", "WHAT").with(as(staff(UserRole.ADMIN, null))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void approve_teacherPhone_givesTeacherMode() throws Exception {
        String phone = phone();
        User teacherUser = staff(UserRole.TEACHER, phone);
        teacherProfile(teacherUser);
        Long requestId = submit(907, phone);
        mvc.perform(post("/api/app-link-requests/{id}/approve", requestId).with(as(staff(UserRole.ADMIN, null))))
            .andExpect(status().isOk());

        auth(initData(907)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.profile.kind").value("TEACHER"))
            .andExpect(jsonPath("$.data.profile.roles[0]").value("TEACHER"))
            .andExpect(jsonPath("$.data.profile.teacher.userId").value(teacherUser.getId()))
            .andExpect(jsonPath("$.data.profile.students", hasSize(0)));
    }
}
