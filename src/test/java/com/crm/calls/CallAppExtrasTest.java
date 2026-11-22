package com.crm.calls;

import com.crm.entity.CallDevice;
import com.crm.entity.Parent;
import com.crm.entity.User;
import com.crm.entity.enums.UserRole;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Qo'ng'iroqlar ilovasi qo'shimchalari (docs/design/calls.md §3.3, §4.7, §4.8): qurilma sinxron sozlamasi
 * (sync_mode / sync_from), terish ekrani qidiruvi, o'z qo'ng'iroqlari va yozuvi, qo'ng'iroqni o'chirish (avto-lid bilan).
 */
class CallAppExtrasTest extends CallsItBase {

    @Autowired private EntityManager em;

    private String token(String registerBody) {
        return JsonPath.read(registerBody, "$.data.deviceToken");
    }

    private String registerWith(User u, String extraJson) throws Exception {
        return token(registerRaw(u, "uid-" + u.getId() + "-" + System.nanoTime(), extraJson)
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private String itemStatus(String syncBody, int i) {
        return JsonPath.read(syncBody, "$.data.results[" + i + "].status");
    }

    private String reason(String syncBody, int i) {
        return JsonPath.read(syncBody, "$.data.results[" + i + "].reason");
    }

    // ── 1. Sinxron sozlamasi ─────────────────────────────────────────────

    @Test
    void register_defaultSyncFromNow_oldCallsSkipped_settingsReturned() throws Exception {
        User sm = staff(UserRole.SALES_MANAGER);
        String body = registerRaw(sm, "def-" + sm.getId(), "").andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.device.syncMode").value("ALL"))
            .andExpect(jsonPath("$.data.device.syncFrom").value("2026-09-15T12:00:00"))
            .andExpect(jsonPath("$.data.syncFrom").value("2026-09-15T12:00:00"))
            .andReturn().getResponse().getContentAsString();
        String token = token(body);

        String r = syncOk(token, item("old", "OUT", phone(), 30, 20), item("new", "OUT", phone(), 0, 20));
        assertThat(itemStatus(r, 0)).isEqualTo("SKIPPED");
        assertThat(reason(r, 0)).isEqualTo("BEFORE_SYNC_FROM");
        assertThat(itemStatus(r, 1)).isEqualTo("CREATED");

        mvc.perform(device(get("/api/calls/app/settings"), token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.syncMode").value("ALL"))
            .andExpect(jsonPath("$.data.syncFrom").value("2026-09-15T12:00:00"));

        // Qayta kirish (o'sha telefon) — saqlangan sozlama qoladi, so'rovdagisi e'tiborsiz
        registerRaw(sm, "def-" + sm.getId(), ",\"syncMode\":\"KNOWN_ONLY\",\"syncFrom\":\"2026-08-01T00:00:00Z\"")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.device.syncMode").value("ALL"))
            .andExpect(jsonPath("$.data.syncFrom").value("2026-09-15T12:00:00"));
    }

    @Test
    void knownOnly_unknownNotStored_noAutoLead_knownStored() throws Exception {
        User sm = staff(UserRole.SALES_MANAGER);
        String token = registerWith(sm, ",\"syncMode\":\"KNOWN_ONLY\",\"syncFrom\":\""
            + clock.instant().minusSeconds(86_400) + "\"");
        String leadPhone = phone();
        Long leadId = lead("Ma'lum Lid", leadPhone, sm);
        String studentPhone = phone();
        Long studentId = student("Bekzod", studentPhone);
        long leadsBefore = count("leads");

        String r = syncOk(token,
            item("u1", "IN", phone(), 10, 30),
            item("u2", "MISSED", phone(), 9, 0),
            item("f1", "OUT", "+4915112345678", 8, 30),
            item("k1", "IN", leadPhone, 7, 30),
            item("k2", "OUT", studentPhone, 6, 30));
        assertThat(List.of(reason(r, 0), reason(r, 1), reason(r, 2)))
            .containsOnly("UNKNOWN_CONTACT");
        assertThat(itemStatus(r, 3)).isEqualTo("CREATED");
        assertThat(itemStatus(r, 4)).isEqualTo("CREATED");
        assertThat(count("leads")).isEqualTo(leadsBefore);
        assertThat(count("calls")).isEqualTo(2);
        assertThat(callRepository.findById(callId(r, 3)).orElseThrow().getLeadId()).isEqualTo(leadId);
        assertThat(callRepository.findById(callId(r, 4)).orElseThrow().getStudentId()).isEqualTo(studentId);
    }

    @Test
    void settings_put_validates_andChangesBehaviour() throws Exception {
        User sm = staff(UserRole.SALES_MANAGER);
        String token = registerWith(sm, "");

        mvc.perform(device(put("/api/calls/app/settings"), token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"syncMode\":\"SOME\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("calls.settings.syncModeInvalid"));
        mvc.perform(device(put("/api/calls/app/settings"), token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"syncFrom\":\"" + clock.instant().plusSeconds(3600) + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("calls.settings.syncFromFuture"));

        mvc.perform(device(put("/api/calls/app/settings"), token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"syncMode\":\"known_only\",\"syncFrom\":\"" + clock.instant().minusSeconds(7200) + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.syncMode").value("KNOWN_ONLY"))
            .andExpect(jsonPath("$.data.syncFrom").value("2026-09-15T10:00:00"));
        CallDevice d = inTx(() -> deviceRepository.findByUserIdAndRevokedAtIsNullOrderByIdDesc(sm.getId()).get(0));
        assertThat(d.getSyncMode()).isEqualTo(CallDevice.SyncMode.KNOWN_ONLY);

        String r = syncOk(token, item("a", "IN", phone(), 60, 10));          // 11:00 ≥ 10:00, lekin notanish
        assertThat(reason(r, 0)).isEqualTo("UNKNOWN_CONTACT");

        // Faqat rejim — syncFrom o'zgarmaydi
        mvc.perform(device(put("/api/calls/app/settings"), token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"syncMode\":\"ALL\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.syncFrom").value("2026-09-15T10:00:00"));
        String r2 = syncOk(token, item("b", "IN", phone(), 60, 10));
        assertThat(itemStatus(r2, 0)).isEqualTo("CREATED");
        assertThat((Boolean) JsonPath.read(r2, "$.data.results[0].contact.autoLeadCreated")).isTrue();
    }

    // ── 2. Qidiruv ───────────────────────────────────────────────────────

    @Test
    void search_byNameAndPhone_permissionsLikeLookup_minLength_limit() throws Exception {
        User sm = staff(UserRole.SALES_MANAGER);
        User other = staff(UserRole.SALES_MANAGER);
        String token = register(sm);
        String ownPhone = phone();
        Long own = lead("Jasurbek Toshmatov", ownPhone, sm);
        lead("Jasurbek Boshqa", phone(), other);
        String studentPhone = phone();
        Long studentId = student("Jasurbek", studentPhone);
        String parentPhone = phone();
        Long parentId = inTx(() -> {
            Parent p = Parent.builder().fullName("Jasurbekning Onasi").phone(parentPhone).isActive(true).build();
            em.persist(p);
            return p.getId();
        });

        String body = mvc.perform(device(get("/api/calls/app/search").param("q", "jasurbek"), token))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> items = JsonPath.read(body, "$.data");
        assertThat(items).hasSize(4);
        Map<String, Object> mine = items.stream().filter(i -> own.equals(num(i.get("leadId")))).findFirst().orElseThrow();
        assertThat(mine.get("phone")).isEqualTo(ownPhone);
        assertThat(mine.get("restricted")).isEqualTo(false);
        Map<String, Object> foreign = items.stream().filter(i -> Boolean.TRUE.equals(i.get("restricted")))
            .findFirst().orElseThrow();
        assertThat(foreign).doesNotContainKeys("leadId", "phone", "subtitle");
        assertThat(foreign.get("name")).isEqualTo("Jasurbek Boshqa");
        assertThat(items).anySatisfy(i -> {
            assertThat(i.get("type")).isEqualTo("STUDENT");
            assertThat(num(i.get("studentId"))).isEqualTo(studentId);
        });
        assertThat(items).anySatisfy(i -> {
            assertThat(i.get("type")).isEqualTo("PARENT");
            assertThat(num(i.get("parentId"))).isEqualTo(parentId);
        });

        // Telefon bo'yicha (bo'shliq / plyus bilan ham) — oxirgi 9 raqam
        String digits = studentPhone.substring(4);
        mvc.perform(device(get("/api/calls/app/search").param("q", "+998 " + digits), token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].type").value("STUDENT"));

        // SUPER_ADMIN — boshqa operatorning lidi ham to'liq
        String saToken = register(staff(UserRole.SUPER_ADMIN));
        mvc.perform(device(get("/api/calls/app/search").param("q", "Jasurbek Boshqa"), saToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].restricted").value(false))
            .andExpect(jsonPath("$.data[0].leadId").isNumber());

        mvc.perform(device(get("/api/calls/app/search").param("q", " j "), token))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("calls.search.tooShort"));

        for (int i = 0; i < 25; i++) {
            lead("Limitbek " + i, phone(), sm);
        }
        mvc.perform(device(get("/api/calls/app/search").param("q", "limitbek"), token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(20));
    }

    private static Long num(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }

    // ── 3. O'z qo'ng'iroqlari va yozuvi ──────────────────────────────────

    @Test
    void myCalls_ownOnly_recordedFilter_streamWithRange() throws Exception {
        User sm = staff(UserRole.SALES_MANAGER);
        String token = register(sm);
        String r = syncOk(token, item("c1", "OUT", phone(), 30, 40), item("c2", "IN", phone(), 20, 10),
            item("c3", "OUT", phone(), 10, 5));
        Long recorded = callId(r, 0);
        uploadRecording(token, recorded, "OWN-AUDIO-0123456789".getBytes(StandardCharsets.UTF_8));
        User other = staff(UserRole.SALES_MANAGER);
        String otherToken = register(other);
        Long foreign = callId(syncOk(otherToken, item("o1", "OUT", phone(), 5, 5)), 0);
        uploadRecording(otherToken, foreign, "OTHER-AUDIO".getBytes(StandardCharsets.UTF_8));

        mvc.perform(device(get("/api/calls/app/calls"), token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(3))
            .andExpect(jsonPath("$.data.content[0].id").value(callId(r, 2)));
        mvc.perform(device(get("/api/calls/app/calls").param("recorded", "true"), token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].id").value(recorded))
            .andExpect(jsonPath("$.data.content[0].recording.available").value(true));
        mvc.perform(device(get("/api/calls/app/calls").param("recorded", "false"), token))
            .andExpect(jsonPath("$.data.totalElements").value(2));
        mvc.perform(device(get("/api/calls/app/calls").param("from", "2026-09-16"), token))
            .andExpect(jsonPath("$.data.totalElements").value(0));

        mvc.perform(device(get("/api/calls/app/calls/" + recorded + "/recording"), token))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(
                "OWN-AUDIO-0123456789".getBytes(StandardCharsets.UTF_8)));
        mvc.perform(device(get("/api/calls/app/calls/" + recorded + "/recording"), token)
                .header(HttpHeaders.RANGE, "bytes=0-2"))
            .andExpect(status().isPartialContent())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string("OWN"));
        mvc.perform(device(get("/api/calls/app/calls/" + foreign + "/recording"), token))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("calls.notFound"));
    }

    // ── 4. O'chirish ─────────────────────────────────────────────────────

    @Test
    void deleteCall_ownWithin7Days_withRecordingFile() throws Exception {
        User sm = staff(UserRole.SALES_MANAGER);
        String token = register(sm);
        String leadPhone = phone();
        Long leadId = lead("Oddiy Lid", leadPhone, sm);
        String r = syncOk(token, item("d1", "OUT", leadPhone, 30, 40), item("d2", "OUT", phone(), 20, 10));
        Long callId = callId(r, 0);
        String rec = uploadRecording(token, callId, "DEL-AUDIO".getBytes(StandardCharsets.UTF_8));
        assertThat(Files.exists(recordingFile(rec))).isTrue();

        User other = staff(UserRole.SALES_MANAGER);
        mvc.perform(device(delete("/api/calls/app/calls/" + callId), register(other)))
            .andExpect(status().isNotFound());

        mvc.perform(device(delete("/api/calls/app/calls/" + callId), token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.recordingDeleted").value(true))
            .andExpect(jsonPath("$.data.leadDeleted").value(false))
            .andExpect(jsonPath("$.data.leadKeptReason").value("NOT_AUTO_LEAD"));
        assertThat(callRepository.findById(callId)).isEmpty();
        assertThat(count("call_recordings")).isZero();
        assertThat(Files.exists(recordingFile(rec))).isFalse();
        assertThat(leadRepository.findById(leadId)).isPresent();

        Long old = callId(r, 1);
        jdbc.update("UPDATE calls SET created_at = ? WHERE id = ?", LocalDateTime.now(clock).minusDays(8), old);
        mvc.perform(device(delete("/api/calls/app/calls/" + old), token))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("calls.delete.tooOld"));
        assertThat(callRepository.findById(old)).isPresent();
    }

    @Test
    void deleteCall_autoLeadWithoutActivity_deletedViaLeadService_withActivity_kept() throws Exception {
        User sm = staff(UserRole.SALES_MANAGER);
        String token = register(sm);

        // a) avto-lid, boshqa faollik yo'q → lid ham o'chadi (audit LEAD_DELETED, CALL_APP)
        String p1 = phone();
        String r1 = syncOk(token, item("a1", "MISSED", p1, 10, 0));
        Long call1 = callId(r1, 0);
        Long lead1 = num(JsonPath.read(r1, "$.data.results[0].contact.leadId"));
        assertThat((Boolean) JsonPath.read(r1, "$.data.results[0].contact.autoLeadCreated")).isTrue();
        mvc.perform(device(delete("/api/calls/app/calls/" + call1), token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.leadId").value(lead1))
            .andExpect(jsonPath("$.data.leadDeleted").value(true));
        assertThat(leadRepository.findById(lead1)).isEmpty();
        assertThat(callRepository.findById(call1)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE lead_id = ?", Long.class, lead1)).isZero();
        assertThat(jdbc.queryForObject("SELECT details_json FROM audit_logs WHERE action = 'LEAD_DELETED' "
            + "AND entity_id = ?", String.class, lead1)).contains("\"mode\":\"CALL_APP\"");

        // b) avto-lid, keyin shu raqamdan yana qo'ng'iroq (faollik) → faqat qo'ng'iroq o'chadi
        String p2 = phone();
        String r2 = syncOk(token, item("b1", "IN", p2, 10, 20), item("b2", "OUT", p2, 5, 30));
        Long call2 = callId(r2, 0);
        Long lead2 = num(JsonPath.read(r2, "$.data.results[0].contact.leadId"));
        mvc.perform(device(delete("/api/calls/app/calls/" + call2), token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.leadDeleted").value(false))
            .andExpect(jsonPath("$.data.leadKeptReason").value("HAS_ACTIVITY"));
        assertThat(leadRepository.findById(lead2)).isPresent();
        assertThat(callRepository.findById(call2)).isEmpty();
        assertThat(callRepository.findById(callId(r2, 1))).isPresent();

        // c) avto-lid nomi o'zgartirilgan (xodim ishlagan) → lid qoladi
        String p3 = phone();
        String r3 = syncOk(token, item("c1", "IN", p3, 10, 20));
        Long lead3 = num(JsonPath.read(r3, "$.data.results[0].contact.leadId"));
        jdbc.update("UPDATE leads SET full_name = 'Sardor Aliyev' WHERE id = ?", lead3);
        mvc.perform(device(delete("/api/calls/app/calls/" + callId(r3, 0)), token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.leadKeptReason").value("HAS_ACTIVITY"));
        assertThat(leadRepository.findById(lead3)).isPresent();
    }
}
