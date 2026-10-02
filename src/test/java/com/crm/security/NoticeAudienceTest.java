package com.crm.security;

import com.crm.entity.User;
import com.crm.entity.enums.UserRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * N-01: e'lon auditoriyasi — rollar to'plami; lenta, /unread-count, o'qish va detal joriy rol bo'yicha;
 * eski {@code publishedTo}/{@code targetRole} (so'rovda ham, bazadagi eski yozuvda ham) o'qiladi.
 */
class NoticeAudienceTest extends Phase5ItBase {

    @Autowired
    ObjectMapper objectMapper;

    private long create(User admin, String audienceJson) throws Exception {
        String body = mvc.perform(post("/api/notices").with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"E'lon\",\"content\":\"Matn\"" + audienceJson + "}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data").get("id").asLong();
    }

    private void expectFeed(User u, long id, boolean visible) throws Exception {
        mvc.perform(get("/api/notices/active").with(as(u)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[*].id",
                visible ? hasItem((int) id) : not(hasItem((int) id))));
        mvc.perform(get("/api/notices/" + id).with(as(u)))
            .andExpect(visible ? status().isOk() : status().isNotFound());
    }

    @Test
    void targetRoles_filterFeedDetailAndUnreadCount() throws Exception {
        User admin = newUser(UserRole.ADMIN);
        User teacher = newUser(UserRole.TEACHER);
        User accountant = newUser(UserRole.ACCOUNTANT);

        long forTeachers = create(admin, ",\"targetRoles\":[\"TEACHER\"]");
        long forAll = create(admin, ",\"targetRoles\":[]");
        long legacyRequest = create(admin, ",\"publishedTo\":\"TEACHERS\"");   // eski frontend
        long legacySingle = create(admin, ",\"targetRole\":\"ACCOUNTANT\"");

        expectFeed(teacher, forTeachers, true);
        expectFeed(teacher, forAll, true);
        expectFeed(teacher, legacyRequest, true);
        expectFeed(teacher, legacySingle, false);
        expectFeed(accountant, forTeachers, false);
        expectFeed(accountant, legacyRequest, false);
        expectFeed(accountant, legacySingle, true);

        mvc.perform(get("/api/notices/unread-count").with(as(teacher)))
            .andExpect(jsonPath("$.data.count").value(3));
        mvc.perform(get("/api/notices/unread-count").with(as(accountant)))
            .andExpect(jsonPath("$.data.count").value(2));

        // Begona e'lonni o'qildi deb belgilab bo'lmaydi; read-all faqat o'ziniki
        mvc.perform(post("/api/notices/" + forTeachers + "/read").with(as(accountant)))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/notices/read-all").with(as(accountant))).andExpect(status().isOk());
        mvc.perform(get("/api/notices/unread-count").with(as(accountant)))
            .andExpect(jsonPath("$.data.count").value(0));
        mvc.perform(get("/api/notices/unread-count").with(as(teacher)))
            .andExpect(jsonPath("$.data.count").value(3));

        // Javob shakli: yangi va eski maydonlar izchil
        mvc.perform(get("/api/notices/" + legacyRequest).with(as(admin)))
            .andExpect(jsonPath("$.data.targetRoles[0]").value("TEACHER"))
            .andExpect(jsonPath("$.data.audienceAll").value(false))
            .andExpect(jsonPath("$.data.publishedTo").value("ROLES"));
        mvc.perform(get("/api/notices/" + forAll).with(as(admin)))
            .andExpect(jsonPath("$.data.audienceAll").value(true))
            .andExpect(jsonPath("$.data.publishedTo").value("ALL"));
    }

    @Test
    void legacyRowsInDatabase_areStillFiltered() throws Exception {
        User admin = newUser(UserRole.ADMIN);
        User teacher = newUser(UserRole.TEACHER);
        User salesManager = newUser(UserRole.SALES_MANAGER);
        // V60 bajarilmagan bazadagi eski yozuv: rollar jadvali bo'sh, published_to = TEACHERS
        jdbc.update("INSERT INTO notices (uuid, title, content, published_to, is_active, is_published,"
                + " published_at, created_at) VALUES (?, 'Eski', 'Matn', 'TEACHERS', TRUE, TRUE, ?, ?)",
            UUID.randomUUID(), LocalDateTime.now().minusDays(1), LocalDateTime.now().minusDays(1));
        long legacyId = jdbc.queryForObject("SELECT MAX(id) FROM notices", Long.class);

        expectFeed(teacher, legacyId, true);
        expectFeed(salesManager, legacyId, false);
        mvc.perform(get("/api/notices/" + legacyId).with(as(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.targetRoles[0]").value("TEACHER"));
    }

    @Test
    void nonManagerList_hidesDraftsAndForeignAudience_putWithoutAudienceKeepsIt() throws Exception {
        User admin = newUser(UserRole.ADMIN);
        User teacher = newUser(UserRole.TEACHER);
        long draft = create(admin, ",\"isPublished\":false");
        long forSales = create(admin, ",\"targetRoles\":[\"SALES_MANAGER\"]");
        long forTeacher = create(admin, ",\"targetRoles\":[\"TEACHER\",\"ADMIN\"]");

        mvc.perform(get("/api/notices").with(as(teacher)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[*].id", hasItem((int) forTeacher)))
            .andExpect(jsonPath("$.data.content[*].id", not(hasItem((int) draft))))
            .andExpect(jsonPath("$.data.content[*].id", not(hasItem((int) forSales))));
        mvc.perform(get("/api/notices").with(as(admin)))
            .andExpect(jsonPath("$.data.content[*].id", hasItem((int) draft)))
            .andExpect(jsonPath("$.data.content[*].id", hasItem((int) forSales)));

        // PUT da auditoriya yuborilmasa — o'zgarmaydi (avval targetRole har tahrirda o'chardi)
        mvc.perform(put("/api/notices/" + forSales).with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Yangi sarlavha\",\"content\":\"Matn\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.targetRoles[0]").value("SALES_MANAGER"));
        mvc.perform(put("/api/notices/" + forSales).with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"T\",\"content\":\"M\",\"publishedTo\":\"ADMINS\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("notice.audience.invalid"));
    }
}
