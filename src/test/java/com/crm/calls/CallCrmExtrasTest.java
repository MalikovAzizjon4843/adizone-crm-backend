package com.crm.calls;

import com.crm.entity.User;
import com.crm.entity.enums.UserRole;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Frontend / Android bo'shliqlari: PHONE_CALL manbasi nomi va ikonka kodi, {@code GET /api/calls?recorded},
 * {@code GET /api/calls/users}, qurilmalar ro'yxatidagi sinxron sozlamasi va heartbeat ruxsatlari, {@code crmWebUrl}.
 */
class CallCrmExtrasTest extends CallsItBase {

    private static final byte[] AUDIO = "AUDIO-0123456789".getBytes(StandardCharsets.UTF_8);

    // ── 1. Manba PHONE_CALL ──────────────────────────────────────────────

    @Test
    void leadSources_includePhoneCall_withLabelAndIcon_perLanguage_andAnalyticsLabel() throws Exception {
        User sa = staff(UserRole.SUPER_ADMIN);
        String body = mvc.perform(get("/api/enums/lead-sources").with(as(staff(UserRole.SALES_MANAGER))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> items = JsonPath.read(body, "$.data");
        assertThat(items).extracting(m -> m.get("value")).contains("WEBSITE", "SELF_CALL", "PHONE_CALL");
        assertThat(items.get(items.size() - 1)).containsEntry("value", "PHONE_CALL")
            .containsEntry("label", "Qo'ng'iroq (ilova)").containsEntry("icon", "smartphone");
        assertThat(items).allSatisfy(m -> assertThat(m.get("icon")).isNotNull());

        mvc.perform(get("/api/enums/lead-sources").with(as(sa)).header(HttpHeaders.ACCEPT_LANGUAGE, "ru"))
            .andExpect(jsonPath("$.data[?(@.value == 'PHONE_CALL')].label").value("Звонок (приложение)"));
        mvc.perform(get("/api/enums/lead-sources").with(as(sa)).header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
            .andExpect(jsonPath("$.data[?(@.value == 'PHONE_CALL')].label").value("Phone call (app)"));
        // O'quvchi manbalarida PHONE_CALL yo'q (faqat lid manbasi), lekin ikonka bor
        mvc.perform(get("/api/enums/student-sources").with(as(sa)))
            .andExpect(jsonPath("$.data[?(@.value == 'PHONE_CALL')]").isEmpty())
            .andExpect(jsonPath("$.data[0].icon").value("globe"));

        // Avto-lid → "Manbalar" va overview'da shu nom
        String token = register(staff(UserRole.SALES_MANAGER));
        String r = syncOk(token, item("auto", "MISSED", phone(), 5, 0));
        Number leadId = JsonPath.read(r, "$.data.results[0].contact.leadId");
        jdbc.update("UPDATE leads SET created_at = ? WHERE id = ?", LocalDateTime.now(clock).minusHours(1),
            leadId.longValue());
        mvc.perform(get("/api/analytics/sources").param("from", "2026-09-15").param("to", "2026-09-15")
                .with(as(sa)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.rows[?(@.key == 'PHONE_CALL')].label").value("Qo'ng'iroq (ilova)"));
    }

    // ── 2. GET /api/calls?recorded ───────────────────────────────────────

    @Test
    void callsList_recordedFilter_isServerSide_paginationCorrect() throws Exception {
        User sm = staff(UserRole.SALES_MANAGER);
        String token = register(sm);
        String r = syncOk(token, item("a", "OUT", phone(), 50, 10), item("b", "OUT", phone(), 40, 10),
            item("c", "OUT", phone(), 30, 10), item("d", "IN", phone(), 20, 10), item("e", "IN", phone(), 10, 10));
        for (int i : new int[]{0, 2, 4}) {
            uploadRecording(token, callId(r, i), (new String(AUDIO, StandardCharsets.UTF_8) + i)
                .getBytes(StandardCharsets.UTF_8));
        }
        // Retention o'chirgan yozuv — "recorded" emas
        jdbc.update("UPDATE call_recordings SET path = NULL, deleted_at = ? WHERE call_id = ?",
            LocalDateTime.now(clock), callId(r, 4));
        User other = staff(UserRole.SALES_MANAGER);
        String otherToken = register(other);
        uploadRecording(otherToken, callId(syncOk(otherToken, item("x", "OUT", phone(), 5, 5)), 0), AUDIO);
        User sa = staff(UserRole.SUPER_ADMIN);

        mvc.perform(get("/api/calls").param("recorded", "true").param("size", "1").with(as(sa)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(3))
            .andExpect(jsonPath("$.data.totalPages").value(3))
            .andExpect(jsonPath("$.data.content.length()").value(1));
        mvc.perform(get("/api/calls").param("recorded", "true").param("userId", String.valueOf(sm.getId()))
                .with(as(sa)))
            .andExpect(jsonPath("$.data.totalElements").value(2))
            .andExpect(jsonPath("$.data.content[0].id").value(callId(r, 2)))
            .andExpect(jsonPath("$.data.content[1].id").value(callId(r, 0)));
        mvc.perform(get("/api/calls").param("recorded", "false").param("userId", String.valueOf(sm.getId()))
                .with(as(sa)))
            .andExpect(jsonPath("$.data.totalElements").value(3));
        // SM — faqat o'ziniki
        mvc.perform(get("/api/calls").param("recorded", "true").with(as(sm)))
            .andExpect(jsonPath("$.data.totalElements").value(2));
        mvc.perform(get("/api/calls").with(as(sa)))
            .andExpect(jsonPath("$.data.totalElements").value(6));
    }

    // ── 3. Qurilmalar ro'yxati ───────────────────────────────────────────

    @Test
    void devicesList_showsSyncSettings_andLastHeartbeatPermissions() throws Exception {
        User sm = staff(UserRole.SALES_MANAGER);
        String body = registerRaw(sm, "dev-" + sm.getId(), ",\"syncMode\":\"KNOWN_ONLY\"")
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.data.deviceToken");
        User sa = staff(UserRole.SUPER_ADMIN);

        mvc.perform(get("/api/calls/devices").with(as(sa)))
            .andExpect(jsonPath("$.data[?(@.user.id == " + sm.getId() + ")].syncMode").value("KNOWN_ONLY"))
            .andExpect(jsonPath("$.data[?(@.user.id == " + sm.getId() + ")].syncFrom").value("2026-09-15T12:00:00"))
            .andExpect(jsonPath("$.data[?(@.user.id == " + sm.getId() + ")].lastHeartbeatAt").value(
                org.hamcrest.Matchers.contains((Object) null)));

        clock.setDateTime(LocalDateTime.of(2026, 9, 15, 13, 30));
        mvc.perform(device(post("/api/calls/app/heartbeat"), token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissions\":{\"callLog\":true,\"recordingsFolder\":false,"
                    + "\"batteryOptimizationIgnored\":true}}"))
            .andExpect(status().isOk());
        String list = mvc.perform(get("/api/calls/devices").with(as(sa)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Map<String, Object> d = JsonPath.<List<Map<String, Object>>>read(list,
            "$.data[?(@.user.id == " + sm.getId() + ")]").get(0);
        assertThat(d.get("lastHeartbeatAt")).isEqualTo("2026-09-15T13:30:00");
        assertThat(d.get("permissions")).isEqualTo(Map.of("callLog", true, "recordingsFolder", false,
            "batteryOptimizationIgnored", true));
        assertThat(d.get("active")).isEqualTo(true);
    }

    // ── 4. GET /api/calls/users ──────────────────────────────────────────

    @Test
    void callUsers_deviceOrCalls_salesHeadIncluded_scopeLikeList() throws Exception {
        User head = staff(UserRole.SALES_HEAD);
        register(head);                                                   // qurilma bor, qo'ng'iroq yo'q
        User sm = staff(UserRole.SALES_MANAGER);
        String smToken = register(sm);
        syncOk(smToken, item("u1", "OUT", phone(), 10, 30));
        User revoked = staff(UserRole.SALES_MANAGER);
        register(revoked);
        jdbc.update("UPDATE call_devices SET revoked_at = ? WHERE user_id = ?", LocalDateTime.now(clock),
            revoked.getId());                                             // qurilmasi revoke, qo'ng'irog'i yo'q
        User loggedOut = staff(UserRole.ADMIN);
        String loToken = register(loggedOut);
        syncOk(loToken, item("u2", "IN", phone(), 5, 30));
        mvc.perform(device(post("/api/calls/app/logout"), loToken)).andExpect(status().isOk());
        User nobody = staff(UserRole.SALES_MANAGER);

        String body = mvc.perform(get("/api/calls/users").with(as(staff(UserRole.ADMIN))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> rows = JsonPath.read(body, "$.data");
        assertThat(rows).extracting(m -> ((Number) m.get("id")).longValue())
            .contains(head.getId(), sm.getId(), loggedOut.getId())
            .doesNotContain(revoked.getId(), nobody.getId());
        Map<String, Object> headRow = rows.stream().filter(m -> ((Number) m.get("id")).longValue() == head.getId())
            .findFirst().orElseThrow();
        assertThat(headRow).containsEntry("role", "SALES_HEAD").containsEntry("activeDevice", true)
            .containsEntry("active", true);
        assertThat(headRow.get("lastCallAt")).isNull();
        Map<String, Object> loRow = rows.stream().filter(m -> ((Number) m.get("id")).longValue() == loggedOut.getId())
            .findFirst().orElseThrow();
        assertThat(loRow).containsEntry("activeDevice", false).containsEntry("lastCallAt", "2026-09-15T11:55:00");

        mvc.perform(get("/api/calls/users").with(as(head)))
            .andExpect(jsonPath("$.data.length()").value(rows.size()));
        mvc.perform(get("/api/calls/users").with(as(sm)))
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].id").value(sm.getId()));
        mvc.perform(get("/api/calls/users").with(as(nobody)))
            .andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/api/calls/users").with(as(staff(UserRole.TEACHER)))).andExpect(status().isForbidden());
        mvc.perform(get("/api/calls/users").with(as(staff(UserRole.ACCOUNTANT)))).andExpect(status().isForbidden());
    }

    // ── 5. crmWebUrl ─────────────────────────────────────────────────────

    @Test
    void crmWebUrl_inRegisterHeartbeatAndSettings() throws Exception {
        User sm = staff(UserRole.SALES_MANAGER);
        String body = registerRaw(sm, "url-" + sm.getId()).andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.settings.crmWebUrl").value("https://admin.adizone.uz"))
            .andExpect(jsonPath("$.data.device.crmWebUrl").value("https://admin.adizone.uz"))
            .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.data.deviceToken");
        mvc.perform(device(get("/api/calls/app/settings"), token))
            .andExpect(jsonPath("$.data.crmWebUrl").value("https://admin.adizone.uz"));
        mvc.perform(device(post("/api/calls/app/heartbeat"), token).contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(jsonPath("$.data.settings.crmWebUrl").value("https://admin.adizone.uz"));

        String old = callsProperties.getCrmWebUrl();
        try {
            callsProperties.setCrmWebUrl("https://crm.example.test");
            mvc.perform(device(get("/api/calls/app/settings"), token))
                .andExpect(jsonPath("$.data.crmWebUrl").value("https://crm.example.test"));
        } finally {
            callsProperties.setCrmWebUrl(old);
        }
        // lookup.crmPath bilan birga — "CRM'da ochish"
        String leadPhone = phone();
        Long leadId = lead("Ochiladigan Lid", leadPhone, sm);
        mvc.perform(device(get("/api/calls/app/lookup").param("phone", leadPhone), token))
            .andExpect(jsonPath("$.data.crmPath").value("/leads?lead=" + leadId));
    }
}
