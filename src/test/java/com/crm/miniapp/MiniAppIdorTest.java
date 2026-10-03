package com.crm.miniapp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * IDOR (docs/design/telegram-platform.md §6, §8): identity A boshqa o'quvchining {@code studentId} si bilan
 * har {@code /api/app/**} ekranida 403 {@code app.student.forbidden}; o'zinikida — 200.
 */
class MiniAppIdorTest extends MiniAppItBase {

    private String tokenA;
    private Long ownStudent;
    private Long foreignStudent;

    @BeforeEach
    void setUp() throws Exception {
        String phoneA = phone();
        ownStudent = student("Ali", "Karimov", phoneA, null);
        String phoneB = phone();
        foreignStudent = student("Begona", "Bola", phoneB, null);
        shareContact(701, phoneA);
        shareContact(702, phoneB);
        tokenA = appToken(701);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/app/home", "/api/app/schedule", "/api/app/attendance", "/api/app/payments",
        "/api/app/profile"})
    void foreignStudentId_forbidden(String path) throws Exception {
        mvc.perform(get(path).param("studentId", foreignStudent.toString())
                .header("Authorization", bearer(tokenA)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("app.student.forbidden"));
        // Mavjud bo'lmagan id ham — 403 (mavjudligini oshkor qilmaydi)
        mvc.perform(get(path).param("studentId", "987654321").header("Authorization", bearer(tokenA)))
            .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/app/home", "/api/app/schedule", "/api/app/attendance", "/api/app/payments",
        "/api/app/profile"})
    void ownStudentId_andDefault_ok(String path) throws Exception {
        mvc.perform(get(path).param("studentId", ownStudent.toString()).header("Authorization", bearer(tokenA)))
            .andExpect(status().isOk());
        mvc.perform(get(path).header("Authorization", bearer(tokenA)))
            .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/app/home", "/api/app/schedule", "/api/app/attendance", "/api/app/payments",
        "/api/app/profile", "/api/app/me"})
    void noToken_401(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("app.auth.unauthorized"));
    }
}
