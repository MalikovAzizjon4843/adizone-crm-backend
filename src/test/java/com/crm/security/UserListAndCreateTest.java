package com.crm.security;

import com.crm.entity.User;
import com.crm.entity.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** U-06 (band login → 409) va U-10 (GET /api/users — sahifalash, filtrlar, eski List shakli). */
class UserListAndCreateTest extends Phase5ItBase {

    private User userNamed(String lastName, UserRole role, boolean active) {
        User u = newUser(role);
        jdbc.update("UPDATE users SET last_name = ?, is_active = ? WHERE id = ?", lastName, active, u.getId());
        return reload(u);
    }

    @Test
    void createUser_takenUsername_409_caseInsensitive_noAlreadyExists200() throws Exception {
        User admin = newUser(UserRole.ADMIN);
        User existing = newUser(UserRole.TEACHER);
        for (String username : new String[]{existing.getUsername(), existing.getUsername().toUpperCase()}) {
            mvc.perform(post("/api/users").with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"firstName\":\"A\",\"lastName\":\"B\",\"username\":\"" + username
                        + "\",\"password\":\"Parol12345\",\"role\":\"TEACHER\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("user.username.taken"));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE LOWER(username) = LOWER(?)",
            Integer.class, existing.getUsername())).isEqualTo(1);
    }

    @Test
    void listUsers_legacyListWithoutPage_pagedWithPage_filters() throws Exception {
        User admin = newUser(UserRole.SUPER_ADMIN);
        String tag = "Qx" + UUID.randomUUID().toString().substring(0, 6);
        userNamed(tag + "a", UserRole.TEACHER, true);
        userNamed(tag + "b", UserRole.TEACHER, false);
        userNamed(tag + "c", UserRole.ACCOUNTANT, true);

        // page yo'q — eski shakl: data = massiv
        mvc.perform(get("/api/users").param("q", tag.toLowerCase()).with(as(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(3));

        // page bor — PageResponse, familiya bo'yicha
        mvc.perform(get("/api/users").param("q", tag).param("page", "0").param("size", "2").with(as(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content.length()").value(2))
            .andExpect(jsonPath("$.data.totalElements").value(3))
            .andExpect(jsonPath("$.data.content[0].lastName").value(tag + "a"));

        mvc.perform(get("/api/users").param("q", tag).param("role", "TEACHER").param("active", "true")
                .param("page", "0").with(as(admin)))
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].lastName").value(tag + "a"));
        mvc.perform(get("/api/users").param("q", tag).param("role", "TEACHER").param("role", "ACCOUNTANT")
                .with(as(admin)))
            .andExpect(jsonPath("$.data.length()").value(3))
            .andExpect(jsonPath("$.data[*].role", everyItem(org.hamcrest.Matchers.in(
                new String[]{"TEACHER", "ACCOUNTANT"}))));
        mvc.perform(get("/api/users").param("q", tag).param("active", "false").with(as(admin)))
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].isActive", is(false)));

        // LIKE belgilari oddiy matn: "%" hech narsaga mos kelmaydi (hammasini emas)
        mvc.perform(get("/api/users").param("q", "%" + tag + "%x").with(as(admin)))
            .andExpect(jsonPath("$.data.length()").value(0));
        // Noto'g'ri rol — 400 (500 emas)
        mvc.perform(get("/api/users").param("role", "NOPE").with(as(admin)))
            .andExpect(status().isBadRequest());
    }
}
