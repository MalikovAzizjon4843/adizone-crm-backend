package com.crm.security;

import com.crm.entity.RefreshToken;
import com.crm.entity.User;
import com.crm.entity.enums.UserRole;
import com.crm.repository.RefreshTokenRepository;
import com.crm.security.jwt.JwtHandshakeInterceptor;
import com.crm.security.jwt.JwtUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * U-02 (P0) + Q1: STUDENT/PARENT logini bloklangan, "authenticated" endpointlar faqat xodimlarga.
 *
 * <p>Avval admin yaratgan o'quvchi logini {@code GET /api/search} orqali barcha o'quvchilarning
 * telefonini olardi; chat, e'lonlar, fayl yuklash va {@code anyRequest()} ham ochiq edi.
 */
class StaffOnlyAccessTest extends Phase5ItBase {

    @Autowired
    RefreshTokenRepository refreshTokens;
    @Autowired
    JwtHandshakeInterceptor handshakeInterceptor;
    @Autowired
    JwtUtils jwtUtils;
    @Autowired
    UserDetailsService userDetailsService;

    private static String loginBody(String username, String password) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }

    @Test
    void studentAndParentLogin_403_roleNotAllowed_noSession() throws Exception {
        for (UserRole role : List.of(UserRole.STUDENT, UserRole.PARENT)) {
            User u = newUser(role);
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content(loginBody(u.getUsername(), PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("auth.roleNotAllowed"));
            assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?", Long.class, u.getId())).isZero();
        }

        // Noto'g'ri parol — avvalgidek 401 (rol oshkor qilinmaydi)
        User student = newUser(UserRole.STUDENT);
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(loginBody(student.getUsername(), "notTheRightOne")))
            .andExpect(status().isUnauthorized());

        // Xodim — kiradi
        User teacher = newUser(UserRole.TEACHER);
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(loginBody(teacher.getUsername(), PASSWORD)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
    }

    @Test
    void oldStudentRefreshToken_isRevoked_403() throws Exception {
        User student = newUser(UserRole.STUDENT);
        String value = UUID.randomUUID().toString();
        inTx(() -> refreshTokens.save(RefreshToken.builder()
            .token(value).user(student).isRevoked(false)
            .expiresAt(LocalDateTime.now().plusDays(1)).build()));

        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + value + "\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("auth.roleNotAllowed"));
        assertThat(refreshTokens.findByTokenAndIsRevokedFalse(value)).isEmpty();
    }

    @Test
    void formerlyAuthenticatedEndpoints_forbiddenForStudentAndParent() throws Exception {
        fixtures.student();
        for (UserRole role : List.of(UserRole.STUDENT, UserRole.PARENT)) {
            User u = newUser(role);
            mvc.perform(get("/api/search").param("q", "Ali").with(as(u))).andExpect(status().isForbidden());
            mvc.perform(get("/api/notices").with(as(u))).andExpect(status().isForbidden());
            mvc.perform(get("/api/notices/unread-count").with(as(u))).andExpect(status().isForbidden());
            mvc.perform(get("/api/chat/conversations").with(as(u))).andExpect(status().isForbidden());
            mvc.perform(get("/api/chat/users").with(as(u))).andExpect(status().isForbidden());
            mvc.perform(get("/api/lead-stages").with(as(u))).andExpect(status().isForbidden());
            mvc.perform(get("/api/settings/academic-year").with(as(u))).andExpect(status().isOk()); // ommaviy
            mvc.perform(get("/api/auth/me").with(as(u))).andExpect(status().isForbidden());
            mvc.perform(get("/api/enums/payment-methods").with(as(u))).andExpect(status().isForbidden());
            mvc.perform(multipart("/api/files/upload")
                    .file(new MockMultipartFile("file", "a.png", "image/png", new byte[]{1}))
                    .with(as(u)))
                .andExpect(status().isForbidden());
        }

        // Xodimlar uchun o'zgarmagan
        User accountant = newUser(UserRole.ACCOUNTANT);
        mvc.perform(get("/api/search").param("q", "Ali").with(as(accountant))).andExpect(status().isOk());
        mvc.perform(get("/api/notices").with(as(accountant))).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").with(as(accountant))).andExpect(status().isOk());
        mvc.perform(get("/api/enums/payment-methods").with(as(accountant))).andExpect(status().isOk());
    }

    @Test
    void chatHandshake_rejectsStudentAndParentTokens() {
        for (UserRole role : List.of(UserRole.STUDENT, UserRole.PARENT, UserRole.TEACHER)) {
            User u = newUser(role);
            String token = jwtUtils.generateToken(userDetailsService.loadUserByUsername(u.getUsername()));
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws");
            request.setParameter("token", token);
            MockHttpServletResponse response = new MockHttpServletResponse();

            boolean accepted = handshakeInterceptor.beforeHandshake(new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(response), null, new HashMap<>());

            assertThat(accepted).as(role.name()).isEqualTo(role == UserRole.TEACHER);
        }
    }

    @Test
    void studentAccounts_cannotBeCreated() throws Exception {
        User admin = newUser(UserRole.ADMIN);
        mvc.perform(post("/api/users").with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"firstName":"Ali","lastName":"Valiyev","password":"Parol12345","role":"STUDENT"}
                    """))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("user.role.notAllowed"));
        mvc.perform(post("/api/users/create-for-student/1").with(as(admin)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("user.role.notAllowed"));
    }
}
