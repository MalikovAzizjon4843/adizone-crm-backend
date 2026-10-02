package com.crm.security;

import com.crm.entity.Teacher;
import com.crm.entity.User;
import com.crm.entity.enums.UserRole;
import com.crm.repository.TeacherRepository;
import com.crm.security.jwt.JwtUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * U-01 (P0), U-03, U-04, U-05: foydalanuvchilar boshqaruvi.
 */
class UserManagementSecurityTest extends Phase5ItBase {

    @Autowired
    TeacherRepository teacherRepository;
    @Autowired
    JwtUtils jwtUtils;
    @Autowired
    UserDetailsService userDetailsService;

    private String json(String username, String password) {
        return "{\"username\":" + (username == null ? "null" : "\"" + username + "\"")
            + ",\"password\":\"" + password + "\"}";
    }

    private Long teacherLinkedTo(User user) {
        return inTx(() -> {
            Teacher t = teacherRepository.findById(fixtures.teacher()).orElseThrow();
            if (user != null) {
                t.setUser(userRepository.findById(user.getId()).orElseThrow());
            }
            return teacherRepository.save(t).getId();
        });
    }

    private Long teacherUserId(Long teacherId) {
        return jdbc.queryForObject("SELECT user_id FROM teachers WHERE id = ?", Long.class, teacherId);
    }

    // ── U-01 ───────────────────────────────────────────────────────────

    @Test
    void createForTeacher_takenUsername_409_neverLinksExistingUser() throws Exception {
        User admin = newUser(UserRole.ADMIN);
        User accountant = newUser(UserRole.ACCOUNTANT);
        User superAdmin = newUser(UserRole.SUPER_ADMIN);
        Long teacherId = teacherLinkedTo(null);

        for (String taken : new String[]{accountant.getUsername(),
                superAdmin.getUsername().toUpperCase(Locale.ROOT)}) {
            mvc.perform(post("/api/users/create-for-teacher/" + teacherId).with(as(admin))
                    .contentType(MediaType.APPLICATION_JSON).content(json(taken, "Parol12345")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("user.username.taken"));
        }
        assertThat(teacherUserId(teacherId)).isNull();
        assertThat(reload(accountant).getRole()).isEqualTo(UserRole.ACCOUNTANT);

        // Qisqa parol — 400
        mvc.perform(post("/api/users/create-for-teacher/" + teacherId).with(as(admin))
                .contentType(MediaType.APPLICATION_JSON).content(json(null, "1234567")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("user.password.size"));

        // Bo'sh login — yangi TEACHER yaratiladi va bog'lanadi
        mvc.perform(post("/api/users/create-for-teacher/" + teacherId).with(as(admin))
                .contentType(MediaType.APPLICATION_JSON).content(json(null, "Parol12345")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.role").value("TEACHER"));
        Long linked = teacherUserId(teacherId);
        assertThat(linked).isNotNull().isNotIn(accountant.getId(), superAdmin.getId(), admin.getId());

        // Profilda login bor — qayta yaratilmaydi, eski user profilsiz qolmaydi
        mvc.perform(post("/api/users/create-for-teacher/" + teacherId).with(as(admin))
                .contentType(MediaType.APPLICATION_JSON).content(json(null, "Parol12345")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("teacher.user.alreadyLinked"));
        assertThat(teacherUserId(teacherId)).isEqualTo(linked);
    }

    // ── U-03 ───────────────────────────────────────────────────────────

    @Test
    void adminCannotManageOtherAdmins_superAdminCan() throws Exception {
        User admin = newUser(UserRole.ADMIN);
        User otherAdmin = newUser(UserRole.ADMIN);
        User superAdmin = newUser(UserRole.SUPER_ADMIN);
        User teacher = newUser(UserRole.TEACHER);

        mvc.perform(post("/api/users/" + otherAdmin.getId() + "/reset-password").with(as(admin)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("user.manage.adminProtected"));
        mvc.perform(patch("/api/users/" + otherAdmin.getId() + "/status").with(as(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/users/" + otherAdmin.getId() + "/password").with(as(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"newPassword\":\"Yangi12345\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/users/" + teacher.getId()).with(as(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("user.role.adminGrant"));
        mvc.perform(post("/api/users").with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"firstName":"Yangi","lastName":"Admin","password":"Parol12345","role":"ADMIN"}
                    """))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("user.role.adminGrant"));
        assertThat(reload(otherAdmin).getIsActive()).isTrue();
        assertThat(reload(teacher).getRole()).isEqualTo(UserRole.TEACHER);

        // ADMIN o'z darajasidan pastdagilarni boshqaradi
        mvc.perform(post("/api/users/" + teacher.getId() + "/reset-password").with(as(admin)))
            .andExpect(status().isOk());
        // SUPER_ADMIN — hammasini
        mvc.perform(post("/api/users/" + otherAdmin.getId() + "/reset-password").with(as(superAdmin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.temporaryPassword").isNotEmpty());
    }

    // ── U-04 ───────────────────────────────────────────────────────────

    @Test
    void putUser_cannotBypassStatusGuards() throws Exception {
        User superAdmin = newUser(UserRole.SUPER_ADMIN);
        User otherSuperAdmin = newUser(UserRole.SUPER_ADMIN);
        User teacher = newUser(UserRole.TEACHER);

        // O'zini bloklash
        mvc.perform(put("/api/users/" + superAdmin.getId()).with(as(superAdmin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"isActive\":false}"))
            .andExpect(status().isBadRequest());
        // Boshqa SUPER_ADMIN ni bloklash
        mvc.perform(put("/api/users/" + otherSuperAdmin.getId()).with(as(superAdmin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"isActive\":false}"))
            .andExpect(status().isForbidden());
        // O'z rolini tushirish
        mvc.perform(put("/api/users/" + superAdmin.getId()).with(as(superAdmin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("user.role.self"));
        assertThat(reload(superAdmin).getIsActive()).isTrue();
        assertThat(reload(superAdmin).getRole()).isEqualTo(UserRole.SUPER_ADMIN);
        assertThat(reload(otherSuperAdmin).getIsActive()).isTrue();

        // Qiymat o'zgarmasa (eski frontend har tahrirda yuboradi) — tekshiruv ishlamaydi
        mvc.perform(put("/api/users/" + superAdmin.getId()).with(as(superAdmin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstName\":\"Yangi\",\"role\":\"SUPER_ADMIN\",\"isActive\":true}"))
            .andExpect(status().isOk());

        // Oddiy bloklash PUT orqali ham sessiyalarni yopadi
        mvc.perform(put("/api/users/" + teacher.getId()).with(as(superAdmin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"isActive\":false}"))
            .andExpect(status().isOk());
        assertThat(reload(teacher).getIsActive()).isFalse();
    }

    // ── U-05 ───────────────────────────────────────────────────────────

    @Test
    void passwordPolicy_min8() throws Exception {
        User superAdmin = newUser(UserRole.SUPER_ADMIN);
        User teacher = newUser(UserRole.TEACHER);
        mvc.perform(put("/api/users/" + teacher.getId() + "/password").with(as(superAdmin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"newPassword\":\"1234567\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/users").with(as(superAdmin)).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"firstName":"Ali","lastName":"Valiyev","password":"123456","role":"TEACHER"}
                    """))
            .andExpect(status().isBadRequest());
        mvc.perform(put("/api/users/" + teacher.getId() + "/password").with(as(superAdmin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"newPassword\":\"Yangi12345\"}"))
            .andExpect(status().isOk());
    }

    private String bearer(User u) {
        return "Bearer " + jwtUtils.generateToken(userDetailsService.loadUserByUsername(u.getUsername()));
    }

    @Test
    void passwordResetAndChange_invalidateOldAccessTokens() throws Exception {
        User superAdmin = newUser(UserRole.SUPER_ADMIN);
        User teacher = newUser(UserRole.TEACHER);

        String oldToken = bearer(teacher);
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, oldToken))
            .andExpect(status().isOk());

        mvc.perform(post("/api/users/" + teacher.getId() + "/reset-password").with(as(superAdmin)))
            .andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, oldToken))
            .andExpect(status().isUnauthorized());

        // Yangi token ishlaydi; o'z parolini almashtirgach u ham bekor
        String fresh = bearer(teacher);
        jdbc.update("UPDATE users SET password = ? WHERE id = ?",
            passwordEncoder.encode(PASSWORD), teacher.getId());
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, fresh))
            .andExpect(status().isOk());
        mvc.perform(put("/api/auth/change-password").header(HttpHeaders.AUTHORIZATION, fresh)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"Boshqa12345\"}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, fresh))
            .andExpect(status().isUnauthorized());
        assertThat(reload(teacher).getTokenVersion()).isEqualTo(2);
    }
}
