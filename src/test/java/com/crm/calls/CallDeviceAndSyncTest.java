package com.crm.calls;

import com.crm.entity.CallDevice;
import com.crm.entity.Lead;
import com.crm.entity.PhoneCall;
import com.crm.entity.User;
import com.crm.entity.enums.UserRole;
import com.crm.security.jwt.JwtUtils;
import com.crm.util.SourceCatalog;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Qurilma tokeni (ro'yxat, hash, revoke, eskirgan ilova), sinxron idempotentligi, avtomatik lid va dedupe,
 * istisnolar (shaxsiy / xodim / yashirin / eski raqam) — docs/design/calls.md §3–§5.
 */
class CallDeviceAndSyncTest extends CallsItBase {

    @Autowired private JwtUtils jwtUtils;
    @Autowired private UserDetailsService userDetailsService;

    // ── Auth ─────────────────────────────────────────────────────────────

    @Test
    void register_tokenShownOnce_storedAsHash_andAuthenticatesApp() throws Exception {
        User sm = staff(UserRole.SALES_MANAGER);
        String body = registerRaw(sm, "pixel-uid-1").andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.user.id").value(sm.getId()))
            .andExpect(jsonPath("$.data.settings.autoLead").value(true))
            .andExpect(jsonPath("$.data.settings.syncMaxBatch").value(200))
            .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.data.deviceToken");
        assertThat(token).startsWith("cd_").hasSizeGreaterThan(40);

        CallDevice d = inTx(() -> deviceRepository.findByUserIdAndRevokedAtIsNullOrderByIdDesc(sm.getId()).get(0));
        assertThat(d.getTokenHash()).isEqualTo(CallDeviceService.sha256(token)).isNotEqualTo(token).hasSize(64);
        assertThat(d.getModel()).isEqualTo("Pixel 7");

        mvc.perform(device(post("/api/calls/app/heartbeat"), token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"appVersion\":\"1.0.0\",\"permissions\":{\"callLog\":true,\"recordingsFolder\":false,"
                    + "\"batteryOptimizationIgnored\":true}}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.pendingRequests").value(0))
            .andExpect(jsonPath("$.data.upgradeRequired").value(false));
        CallDevice after = inTx(() -> deviceRepository.findById(d.getId()).orElseThrow());
        assertThat(after.getPermCallLog()).isTrue();
        assertThat(after.getPermRecordings()).isFalse();
        assertThat(after.getPermBatteryOpt()).isTrue();
    }

    @Test
    void appChain_rejectsMissingWrongAndAdminJwt() throws Exception {
        User sm = staff(UserRole.SALES_MANAGER);
        register(sm);
        mvc.perform(get("/api/calls/app/exclusions"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("calls.device.unauthorized"));
        mvc.perform(device(get("/api/calls/app/exclusions"), "cd_not-a-real-token"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("calls.device.unauthorized"));
        // Admin JWT qurilma zanjirida yaroqsiz
        String jwt = jwtUtils.generateToken(userDetailsService.loadUserByUsername(sm.getUsername()));
        mvc.perform(get("/api/calls/app/exclusions").header("Authorization", "Bearer " + jwt))
            .andExpect(status().isUnauthorized());
        // Versiya — tokensiz ochiq
        mvc.perform(get("/api/calls/app/version"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.minSupported").value("1.0.0"));
    }

    @Test
    void register_onlyAllowedRoles() throws Exception {
        registerRaw(staff(UserRole.TEACHER), "t-uid").andExpect(status().isForbidden());
        registerRaw(staff(UserRole.ACCOUNTANT), "a-uid").andExpect(status().isForbidden());
        for (UserRole role : List.of(UserRole.SUPER_ADMIN, UserRole.ADMIN, UserRole.SALES_HEAD, UserRole.SALES_MANAGER)) {
            registerRaw(staff(role), "ok-" + role).andExpect(status().isCreated());
        }
    }

    @Test
    void revoke_bySuperAdmin_invalidatesTokenImmediately() throws Exception {
        User sm = staff(UserRole.SALES_MANAGER);
        String token = register(sm);
        Long deviceId = inTx(() -> deviceRepository.findByUserIdAndRevokedAtIsNullOrderByIdDesc(sm.getId()).get(0).getId());

        mvc.perform(get("/api/calls/devices").with(as(staff(UserRole.ADMIN)))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/calls/devices/" + deviceId).with(as(staff(UserRole.ADMIN))))
            .andExpect(status().isForbidden());

        User sa = staff(UserRole.SUPER_ADMIN);
        mvc.perform(get("/api/calls/devices").with(as(sa)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[?(@.id == " + deviceId + ")].active").value(true));
        mvc.perform(delete("/api/calls/devices/" + deviceId).with(as(sa)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.active").value(false));

        mvc.perform(device(get("/api/calls/app/exclusions"), token))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("calls.device.revoked"));
    }

    @Test
    void reRegister_newPhoneRevokesOld_samePhoneRotatesToken_inactiveUserRejected() throws Exception {
        User sm = staff(UserRole.SALES_MANAGER);
        String first = register(sm, "phone-A");
        String rotated = register(sm, "phone-A");
        mvc.perform(device(get("/api/calls/app/exclusions"), first))
            .andExpect(status().isUnauthorized());   // eski token hash almashtirildi
        mvc.perform(device(get("/api/calls/app/exclusions"), rotated)).andExpect(status().isOk());
        assertThat(inTx(() -> deviceRepository.findByUserIdAndRevokedAtIsNullOrderByIdDesc(sm.getId()))).hasSize(1);

        String second = register(sm, "phone-B");
        mvc.perform(device(get("/api/calls/app/exclusions"), rotated))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("calls.device.revoked"));

        // Boshqa xodim shu telefonda kirsa — oldingi hisob o'chadi
        User other = staff(UserRole.SALES_HEAD);
        register(other, "phone-B");
        mvc.perform(device(get("/api/calls/app/exclusions"), second))
            .andExpect(jsonPath("$.code").value("calls.device.revoked"));

        String token = register(staff(UserRole.ADMIN));
        jdbc.update("UPDATE users SET is_active = false WHERE id = (SELECT user_id FROM call_devices WHERE token_hash = ?)",
            CallDeviceService.sha256(token));
        mvc.perform(device(get("/api/calls/app/exclusions"), token))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("calls.device.userInactive"));
    }

    @Test
    void outdatedApp_gets426() throws Exception {
        String token = register(staff(UserRole.SALES_MANAGER));
        String before = callsProperties.getApp().getMinSupportedVersion();
        callsProperties.getApp().setMinSupportedVersion("1.2.0");
        try {
            mvc.perform(device(get("/api/calls/app/exclusions"), token).header(CallDeviceFilter.VERSION_HEADER, "1.1.9"))
                .andExpect(status().is(426))
                .andExpect(jsonPath("$.code").value("calls.app.upgradeRequired"));
            mvc.perform(device(get("/api/calls/app/exclusions"), token).header(CallDeviceFilter.VERSION_HEADER, "1.10.0"))
                .andExpect(status().isOk());
        } finally {
            callsProperties.getApp().setMinSupportedVersion(before);
        }
    }

    // ── Sinxron ──────────────────────────────────────────────────────────

    @Test
    void sync_isIdempotent_andMatchesContacts() throws Exception {
        User sm = staff(UserRole.SALES_MANAGER);
        String token = register(sm);
        String studentPhone = phone();
        Long studentId = student("Aziza", studentPhone);
        String unknownIn = phone();
        String unknownOut = phone();

        String first = syncOk(token,
            item("k1", "IN", "+998 " + studentPhone.substring(4, 6) + " " + studentPhone.substring(6), 30, 75),
            item("k2", "IN", unknownIn.substring(4), 20, 40),          // 9 xonali — kanonikka keltiriladi
            item("k3", "OUT", unknownOut, 10, 0));
        assertThat((Integer) JsonPath.read(first, "$.data.created")).isEqualTo(3);
        assertThat((String) JsonPath.read(first, "$.data.results[0].contact.type")).isEqualTo("STUDENT");
        assertThat(((Number) JsonPath.read(first, "$.data.results[0].contact.studentId")).longValue()).isEqualTo(studentId);
        assertThat((String) JsonPath.read(first, "$.data.results[1].contact.type")).isEqualTo("LEAD");
        assertThat((Boolean) JsonPath.read(first, "$.data.results[1].contact.autoLeadCreated")).isTrue();
        // Chiquvchi notanish raqam — lid yaratilmaydi
        assertThat((String) JsonPath.read(first, "$.data.results[2].contact.type")).isEqualTo("UNKNOWN");

        String again = syncOk(token,
            item("k1", "IN", studentPhone, 30, 75),
            item("k2", "IN", unknownIn, 20, 40),
            item("k3", "OUT", unknownOut, 10, 0),
            item("k3", "OUT", unknownOut, 10, 0));
        assertThat((Integer) JsonPath.read(again, "$.data.created")).isZero();
        assertThat((Integer) JsonPath.read(again, "$.data.duplicates")).isEqualTo(4);
        for (int i = 0; i < 3; i++) {
            assertThat(callId(again, i)).isEqualTo(callId(first, i));
            assertThat((String) JsonPath.read(again, "$.data.results[" + i + "].status")).isEqualTo("DUPLICATE");
        }
        assertThat(count("calls")).isEqualTo(3);
        assertThat(count("leads")).isEqualTo(1);

        PhoneCall c = inTx(() -> callRepository.findById(callId(first, 0)).orElseThrow());
        assertThat(c.getPhoneNormalized()).isEqualTo(studentPhone);
        assertThat(c.getDurationSec()).isEqualTo(75);
        assertThat(c.getUserId()).isEqualTo(sm.getId());
        assertThat(c.getStartedAt()).isEqualTo(java.time.LocalDateTime.now(clock).minusMinutes(30));
    }

    @Test
    void autoLead_dedupedByPhone_ownerAssigned_flagOff() throws Exception {
        User sm = staff(UserRole.SALES_MANAGER);
        String token = register(sm);
        String caller = phone();
        String body = syncOk(token,
            item("m1", "MISSED", caller, 50, 30),
            item("m2", "MISSED", "8" + caller.substring(4), 40, 0),   // 8 bilan — o'sha raqam
            item("r1", "REJECTED", phone(), 35, 0));                  // REJECTED — avtomatik lid yo'q
        assertThat(count("leads")).isEqualTo(1);
        Long leadId = ((Number) JsonPath.read(body, "$.data.results[0].contact.leadId")).longValue();
        assertThat(((Number) JsonPath.read(body, "$.data.results[1].contact.leadId")).longValue()).isEqualTo(leadId);
        assertThat((java.util.Map<String, Object>) JsonPath.read(body, "$.data.results[1].contact"))
            .doesNotContainKey("autoLeadCreated");
        assertThat((String) JsonPath.read(body, "$.data.results[2].contact.type")).isEqualTo("UNKNOWN");
        // MISSED davomiyligi 0 ga keltiriladi
        assertThat(inTx(() -> callRepository.findById(callId(body, 0)).orElseThrow().getDurationSec())).isZero();

        Lead lead = inTx(() -> {
            Lead l = leadRepository.findById(leadId).orElseThrow();
            l.getAssignedUser().getId();
            return l;
        });
        assertThat(lead.getSource()).isEqualTo(SourceCatalog.PHONE_CALL);
        assertThat(lead.getPhone()).isEqualTo(caller);
        assertThat(lead.getAssignedUser().getId()).isEqualTo(sm.getId());
        assertThat(lead.getFullName()).contains(caller);
        assertThat(lead.getFormat()).isNull();

        // Keyingi partiyada ham — yangi lid emas
        String later = syncOk(token, item("i9", "IN", caller, 5, 12));
        assertThat(((Number) JsonPath.read(later, "$.data.results[0].contact.leadId")).longValue()).isEqualTo(leadId);
        assertThat(count("leads")).isEqualTo(1);

        callsProperties.setAutoLead(false);
        try {
            String off = syncOk(token, item("off1", "IN", phone(), 3, 10));
            assertThat((String) JsonPath.read(off, "$.data.results[0].contact.type")).isEqualTo("UNKNOWN");
            assertThat(count("leads")).isEqualTo(1);
        } finally {
            callsProperties.setAutoLead(true);
        }
    }

    @Test
    void exclusions_staff_hidden_old_andInvalid_areNotStored() throws Exception {
        User sm = staff(UserRole.SALES_MANAGER);
        String token = register(sm);
        String personal = phone();
        String colleague = phone();
        staff(UserRole.TEACHER, colleague);

        mvc.perform(device(put("/api/calls/app/exclusions"), token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"phone\":\"" + personal.substring(4) + "\",\"label\":\"Onam\"},"
                    + "{\"phone\":\"" + personal + "\"}]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].phone").value(personal))
            .andExpect(jsonPath("$.data[0].label").value("Onam"));
        mvc.perform(device(put("/api/calls/app/exclusions"), token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"phone\":\"abc\"}]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("calls.exclusions.phoneInvalid"));

        String body = syncOk(token,
            item("e1", "IN", "+998 " + personal.substring(4), 10, 60),
            item("e2", "OUT", colleague, 9, 60),
            item("e3", "IN", "", 8, 5),
            item("e4", "IN", phone(), 60L * 24 * 31, 5),
            item("e5", "SIDEWAYS", phone(), 7, 5),
            item("", "IN", phone(), 6, 5));
        assertThat((String) JsonPath.read(body, "$.data.results[0].reason")).isEqualTo("EXCLUDED");
        assertThat((String) JsonPath.read(body, "$.data.results[1].reason")).isEqualTo("STAFF");
        assertThat((String) JsonPath.read(body, "$.data.results[2].reason")).isEqualTo("NO_NUMBER");
        assertThat((String) JsonPath.read(body, "$.data.results[3].reason")).isEqualTo("TOO_OLD");
        assertThat((String) JsonPath.read(body, "$.data.results[4].status")).isEqualTo("REJECTED");
        assertThat((String) JsonPath.read(body, "$.data.results[4].reason")).isEqualTo("calls.sync.invalidDirection");
        assertThat((String) JsonPath.read(body, "$.data.results[5].reason")).isEqualTo("calls.sync.invalidKey");
        assertThat((Integer) JsonPath.read(body, "$.data.skipped")).isEqualTo(4);
        assertThat((Integer) JsonPath.read(body, "$.data.rejected")).isEqualTo(2);
        assertThat(count("calls")).isZero();
        assertThat(count("leads")).isZero();
    }

    @Test
    void sync_batchLimit() throws Exception {
        String token = register(staff(UserRole.SALES_MANAGER));
        List<java.util.Map<String, Object>> items = new java.util.ArrayList<>();
        for (int i = 0; i < 201; i++) {
            items.add(item("b" + i, "OUT", phone(), 5, 1));
        }
        sync(token, items).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("calls.sync.tooMany"));
    }
}
