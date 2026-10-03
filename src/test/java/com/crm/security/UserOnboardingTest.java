package com.crm.security;

import com.crm.entity.User;
import com.crm.entity.UserOnboarding;
import com.crm.entity.enums.UserRole;
import com.crm.repository.UserOnboardingRepository;
import com.crm.service.UserOnboardingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Onboarding holati: {@code GET/PUT/DELETE /api/users/me/onboarding} — barcha STAFF rollar, faqat o'z
 * yozuvlari, kalit formati, idempotentlik (V67).
 */
class UserOnboardingTest extends Phase5ItBase {

    private static final String BASE = "/api/users/me/onboarding";

    @Autowired
    private UserOnboardingRepository repository;

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"SUPER_ADMIN", "ADMIN", "SALES_HEAD", "SALES_MANAGER",
        "ACCOUNTANT", "TEACHER"})
    void everyStaffRole_markListReset(UserRole role) throws Exception {
        User u = newUser(role);

        mvc.perform(get(BASE).with(as(u))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data", hasSize(0)));
        mvc.perform(put(BASE + "/{key}", "leads.kanban-v2").with(as(u))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.key").value("leads.kanban-v2"))
            .andExpect(jsonPath("$.data.seenAt").value(startsWith("2026-09-15T12:00")));
        mvc.perform(put(BASE + "/{key}", "dashboard.intro").with(as(u))).andExpect(status().isOk());

        mvc.perform(get(BASE).with(as(u))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data", hasSize(2)))
            .andExpect(jsonPath("$.data[0].key").value("dashboard.intro"))
            .andExpect(jsonPath("$.data[1].key").value("leads.kanban-v2"));

        mvc.perform(delete(BASE).with(as(u))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.deleted").value(2));
        mvc.perform(get(BASE).with(as(u))).andExpect(jsonPath("$.data", hasSize(0)));
        // Bo'sh holatda ham DELETE — xato emas
        mvc.perform(delete(BASE).with(as(u))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.deleted").value(0));
    }

    @Test
    void put_isIdempotent_keepsFirstSeenAt() throws Exception {
        User u = newUser(UserRole.TEACHER);
        mvc.perform(put(BASE + "/{key}", "attendance.intro").with(as(u)))
            .andExpect(jsonPath("$.data.seenAt").value(startsWith("2026-09-15T12:00")));

        clock.setDateTime(LocalDateTime.of(2026, 9, 20, 9, 30));
        mvc.perform(put(BASE + "/{key}", "attendance.intro").with(as(u))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.seenAt").value(startsWith("2026-09-15T12:00")));

        assertThat(repository.findByUserIdOrderByTourKeyAsc(u.getId())).hasSize(1);
    }

    @Test
    void ownRecordsOnly() throws Exception {
        User a = newUser(UserRole.ADMIN);
        User b = newUser(UserRole.ADMIN);
        mvc.perform(put(BASE + "/{key}", "dashboard.intro").with(as(a))).andExpect(status().isOk());

        mvc.perform(get(BASE).with(as(b))).andExpect(jsonPath("$.data", hasSize(0)));
        mvc.perform(delete(BASE).with(as(b))).andExpect(jsonPath("$.data.deleted").value(0));

        mvc.perform(get(BASE).with(as(a))).andExpect(jsonPath("$.data", hasSize(1)));
        // Boshqa foydalanuvchi id si bilan yo'l yo'q — /api/users/{id}/onboarding mavjud emas
        mvc.perform(get("/api/users/{id}/onboarding", a.getId()).with(as(b)))
            .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(403, 404));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Dashboard.intro", "dashboard..intro", ".intro", "intro.", "intro-", "a b",
        "dashboard:intro", "darslar.kirish-ʼ", "ключ"})
    void invalidKey_400(String key) throws Exception {
        User u = newUser(UserRole.SALES_MANAGER);
        mvc.perform(put(BASE + "/{key}", key).with(as(u)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("onboarding.key.invalid"));
        assertThat(repository.countByUserId(u.getId())).isZero();
    }

    @Test
    void keyLength_80ok_81rejected() throws Exception {
        User u = newUser(UserRole.ACCOUNTANT);
        mvc.perform(put(BASE + "/{key}", "a".repeat(80)).with(as(u))).andExpect(status().isOk());
        mvc.perform(put(BASE + "/{key}", "a".repeat(81)).with(as(u)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("onboarding.key.invalid"));
    }

    @Test
    void limitPerUser_409() throws Exception {
        User u = newUser(UserRole.TEACHER);
        inTx(() -> repository.saveAll(IntStream.range(0, UserOnboardingService.MAX_KEYS_PER_USER)
            .mapToObj(i -> UserOnboarding.builder().userId(u.getId()).tourKey("k" + i)
                .seenAt(LocalDateTime.of(2026, 9, 1, 10, 0)).build())
            .toList()));

        mvc.perform(put(BASE + "/{key}", "one.more").with(as(u)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("onboarding.limit"));
        // Mavjud kalitni qayta belgilash limitga tushmaydi
        mvc.perform(put(BASE + "/{key}", "k7").with(as(u))).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"STUDENT", "PARENT"})
    void nonStaff_forbidden(String role) throws Exception {
        mvc.perform(get(BASE).with(org.springframework.security.test.web.servlet.request
                .SecurityMockMvcRequestPostProcessors.user("x").roles(role)))
            .andExpect(status().isForbidden());
        mvc.perform(put(BASE + "/{key}", "dashboard.intro").with(org.springframework.security.test.web.servlet
                .request.SecurityMockMvcRequestPostProcessors.user("x").roles(role)))
            .andExpect(status().isForbidden());
    }

    @Test
    void anonymous_401() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mvc.perform(delete(BASE)).andExpect(status().isUnauthorized());
    }

    /** V67: bazada ham kalit CHECK i va users FK (ON DELETE CASCADE) — faqat PostgreSQL (H2 da migratsiya yo'q). */
    @Test
    void v67_checkAndCascade_onPostgres() {
        if (!isPostgres()) {
            return;
        }
        User u = newUser(UserRole.TEACHER);
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO user_onboarding (user_id, tour_key, seen_at) VALUES (?, 'Bad Key', now())", u.getId()))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        jdbc.update("INSERT INTO user_onboarding (user_id, tour_key, seen_at) VALUES (?, 'ok.key', now())", u.getId());
        jdbc.update("DELETE FROM users WHERE id = ?", u.getId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_onboarding WHERE user_id = ?",
            Integer.class, u.getId())).isZero();
    }
}
