package com.crm.security;

import com.crm.entity.RefreshToken;
import com.crm.entity.Teacher;
import com.crm.entity.User;
import com.crm.entity.enums.UserRole;
import com.crm.repository.RefreshTokenRepository;
import com.crm.repository.TeacherRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** "O'z hisobim" — {@code /api/users/me/**}: barcha xodim rollari, faqat o'zi; login/rol o'zgarmaydi. */
class MyProfileTest extends Phase5ItBase {

    @Autowired TeacherRepository teacherRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired ObjectMapper objectMapper;

    @Test
    void getMe_anyStaffRole_withTeacherLink() throws Exception {
        User teacherUser = newUser(UserRole.TEACHER);
        Long teacherId = fixtures.teacher();
        inTx(() -> {
            Teacher t = teacherRepository.findById(teacherId).orElseThrow();
            t.setUser(userRepository.findById(teacherUser.getId()).orElseThrow());
            teacherRepository.save(t);
        });

        mvc.perform(get("/api/users/me").with(as(teacherUser)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(teacherUser.getId().intValue()))
            .andExpect(jsonPath("$.data.username").value(teacherUser.getUsername()))
            .andExpect(jsonPath("$.data.fullName").value("Test TEACHER"))
            .andExpect(jsonPath("$.data.role").value("TEACHER"))
            .andExpect(jsonPath("$.data.teacherId").value(teacherId.intValue()));

        for (UserRole role : new UserRole[]{UserRole.ACCOUNTANT, UserRole.SALES_MANAGER, UserRole.SALES_HEAD,
                UserRole.ADMIN, UserRole.SUPER_ADMIN}) {
            mvc.perform(get("/api/users/me").with(as(newUser(role))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value(role.name()))
                .andExpect(jsonPath("$.data.teacherId").isEmpty());
        }
        mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
        // Boshqa /api/users/** yo'llari avvalgidek faqat SA/A
        mvc.perform(get("/api/users/" + teacherUser.getId()).with(as(teacherUser))).andExpect(status().isForbidden());
    }

    @Test
    void updateMe_partial_usernameAndRoleUnchanged() throws Exception {
        User u = newUser(UserRole.SALES_MANAGER);
        mvc.perform(put("/api/users/me").with(as(u)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstName\":\" Yangi \",\"phone\":\"+998 90 123 45 67\","
                    + "\"username\":\"hacker\",\"role\":\"SUPER_ADMIN\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.firstName").value("Yangi"))
            .andExpect(jsonPath("$.data.lastName").value("SALES_MANAGER"))
            .andExpect(jsonPath("$.data.phone").value("+998901234567"))
            .andExpect(jsonPath("$.data.username").value(u.getUsername()))
            .andExpect(jsonPath("$.data.role").value("SALES_MANAGER"));
        User saved = reload(u);
        assertThat(saved.getUsername()).isEqualTo(u.getUsername());
        assertThat(saved.getRole()).isEqualTo(UserRole.SALES_MANAGER);

        mvc.perform(put("/api/users/me").with(as(u)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"lastName\":\"   \"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(put("/api/users/me").with(as(u)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"12345\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(put("/api/users/me").with(as(u)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.phone").isEmpty());
    }

    @Test
    void avatar_imageUpTo2Mb() throws Exception {
        User u = newUser(UserRole.ACCOUNTANT);
        mvc.perform(multipart("/api/users/me/avatar").file(new MockMultipartFile("file", "me.png", "image/png", png()))
                .with(as(u)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.avatarUrl").value(startsWith("/api/files/")));
        assertThat(reload(u).getPhotoUrl()).startsWith("/api/files/").endsWith(".png");

        byte[] big = new byte[2 * 1024 * 1024 + 1];
        mvc.perform(multipart("/api/users/me/avatar").file(new MockMultipartFile("file", "big.png", "image/png", big))
                .with(as(u)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("user.avatar.tooLarge"));
        mvc.perform(multipart("/api/users/me/avatar")
                .file(new MockMultipartFile("file", "x.pdf", "application/pdf", "%PDF-1.4".getBytes()))
                .with(as(u)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void changePassword_revokesOtherSessions_returnsNewTokens() throws Exception {
        User u = newUser(UserRole.TEACHER);
        String oldRefresh = UUID.randomUUID().toString();
        inTx(() -> refreshTokenRepository.save(RefreshToken.builder().token(oldRefresh)
            .user(userRepository.findById(u.getId()).orElseThrow())
            .expiresAt(LocalDateTime.now().plusDays(7)).isRevoked(false).build()));
        int versionBefore = reload(u).getTokenVersion();

        mvc.perform(post("/api/users/me/password").with(as(u)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"notmine123\",\"newPassword\":\"YangiParol123\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("user.password.invalid"));
        mvc.perform(post("/api/users/me/password").with(as(u)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"short\"}"))
            .andExpect(status().isBadRequest());
        assertThat(passwordEncoder.matches(PASSWORD, reload(u).getPassword())).isTrue();

        JsonNode tokens = objectMapper.readTree(mvc.perform(post("/api/users/me/password").with(as(u))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"YangiParol123\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()).get("data");
        assertThat(tokens.get("accessToken").asText()).isNotBlank();
        String newRefresh = tokens.get("refreshToken").asText();

        User after = reload(u);
        assertThat(passwordEncoder.matches("YangiParol123", after.getPassword())).isTrue();
        assertThat(after.getTokenVersion()).isEqualTo(versionBefore + 1);
        assertThat(refreshTokenRepository.findByTokenAndIsRevokedFalse(oldRefresh)).isEmpty();
        assertThat(refreshTokenRepository.findByTokenAndIsRevokedFalse(newRefresh)).isPresent();

        // Yangi access token ishlaydi (token versiyasi mos), yangi parol bilan login
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + tokens.get("accessToken").asText()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.username").value(u.getUsername()));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + u.getUsername() + "\",\"password\":\"YangiParol123\"}"))
            .andExpect(status().isOk());
    }

    private static byte[] png() throws Exception {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
