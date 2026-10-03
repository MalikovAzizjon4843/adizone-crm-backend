package com.crm.miniapp;

import com.crm.entity.AppIdentity;
import com.crm.entity.User;
import com.crm.entity.enums.StudentStatus;
import com.crm.entity.enums.UserRole;
import com.crm.repository.UserRepository;
import com.crm.security.CustomUserDetailsService;
import com.crm.security.jwt.JwtUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Mini App kirishi va JWT ajratish (docs/design/telegram-platform.md §3.2, §3.3, §8 "initData", "JWT ajratish").
 */
class MiniAppAuthTest extends MiniAppItBase {

    @Autowired
    private JwtUtils jwtUtils;
    @Autowired
    private CustomUserDetailsService userDetailsService;
    @Autowired
    private UserRepository userRepository;

    // ── initData ─────────────────────────────────────────────────────────

    @Test
    void auth_linkedUser_tokenAndProfile() throws Exception {
        String phone = phone();
        Long a = student("Ali", "Karimov", phone(), phone);
        Long b = student("Vali", "Karimov", phone(), phone);
        shareContact(601, phone);

        auth(initData(601)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.data.expiresIn").value(1800))
            .andExpect(jsonPath("$.data.token").isString())
            .andExpect(jsonPath("$.data.profile.kind").value("PARENT"))
            .andExpect(jsonPath("$.data.profile.phoneMasked").value(phone.substring(0, 4) + " "
                + phone.substring(4, 6) + " *** ** " + phone.substring(11)))
            .andExpect(jsonPath("$.data.profile.defaultStudentId").value(a))
            .andExpect(jsonPath("$.data.profile.students.length()").value(2))
            .andExpect(jsonPath("$.data.profile.students[1].id").value(b))
            .andExpect(jsonPath("$.data.profile.students[1].relation").value("PARENT"))
            .andExpect(jsonPath("$.data.profile.support.phone").value(SUPPORT_PHONE));
    }

    @Test
    void auth_notLinked_403() throws Exception {
        auth(initData(602)).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("app.notLinked"))
            .andExpect(jsonPath("$.data.supportPhone").value(SUPPORT_PHONE));
    }

    @Test
    void auth_tamperedField_401() throws Exception {
        String phone = phone();
        student("Ali", "Karimov", phone, null);
        shareContact(603, phone);
        // Boshqa foydalanuvchi id si bilan almashtirilgan user — imzo mos kelmaydi
        String tampered = initData(603).replace("%22id%22%3A603", "%22id%22%3A604");
        assertThat(tampered).isNotEqualTo(initData(603));
        auth(tampered).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("app.auth.invalidInitData"));
    }

    @Test
    void auth_missingHash_401() throws Exception {
        String data = initData(605);
        auth(data.substring(0, data.indexOf("&hash="))).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("app.auth.invalidInitData"));
    }

    @Test
    void auth_authDateTooOld_401() throws Exception {
        String phone = phone();
        student("Ali", "Karimov", phone, null);
        shareContact(606, phone);
        long twoHoursAgo = clock.instant().getEpochSecond() - 7200;
        auth(initData(606, twoHoursAgo)).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("app.auth.initDataExpired"));
    }

    @Test
    void auth_authDateInFuture_401() throws Exception {
        auth(initData(607, clock.instant().getEpochSecond() + 600)).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("app.auth.invalidInitData"));
    }

    @Test
    void auth_noUserField_401() throws Exception {
        Map<String, String> fields = new TreeMap<>();
        fields.put("auth_date", String.valueOf(clock.instant().getEpochSecond()));
        fields.put("query_id", "AAH");
        auth(signed(fields)).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("app.auth.invalidInitData"));
    }

    @Test
    void auth_blankInitData_400() throws Exception {
        auth("").andExpect(status().isBadRequest());
    }

    // ── JWT ajratish ─────────────────────────────────────────────────────

    @Test
    void appToken_rejectedOnAdminApi() throws Exception {
        String phone = phone();
        student("Ali", "Karimov", phone, null);
        shareContact(610, phone);
        String token = appToken(610);

        mvc.perform(get("/api/students").header("Authorization", bearer(token)))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization", bearer(token)))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/app/me").header("Authorization", bearer(token)))
            .andExpect(status().isOk());
    }

    @Test
    void adminToken_rejectedOnAppApi() throws Exception {
        User admin = inTx(() -> userRepository.save(User.builder()
            .username("miniapp-sa-" + System.nanoTime())
            .password("x").firstName("S").lastName("A")
            .role(UserRole.SUPER_ADMIN).isActive(true).build()));
        String adminToken = jwtUtils.generateToken(userDetailsService.loadUserByUsername(admin.getUsername()));

        mvc.perform(get("/api/auth/me").header("Authorization", bearer(adminToken)))
            .andExpect(status().isOk());
        mvc.perform(get("/api/app/home").header("Authorization", bearer(adminToken)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("app.auth.unauthorized"));
        mvc.perform(get("/api/app/home")).andExpect(status().isUnauthorized());
    }

    @Test
    void appToken_expiresAfter30Minutes() throws Exception {
        String phone = phone();
        student("Ali", "Karimov", phone, null);
        shareContact(611, phone);
        String token = appToken(611);

        clock.setDateTime(java.time.LocalDateTime.ofInstant(clock.instant(), clock.getZone()).plusMinutes(31));
        mvc.perform(get("/api/app/me").header("Authorization", bearer(token)))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void unlinkFromApp_invalidatesTokenImmediately() throws Exception {
        String phone = phone();
        student("Ali", "Karimov", phone, null);
        shareContact(612, phone);
        String token = appToken(612);

        mvc.perform(post("/api/app/profile/unlink").header("Authorization", bearer(token)))
            .andExpect(status().isOk());

        mvc.perform(get("/api/app/me").header("Authorization", bearer(token)))
            .andExpect(status().isUnauthorized());
        auth(initData(612)).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("app.notLinked"));
        AppIdentity identity = identityRepository.findByTelegramUserId(612L).orElseThrow();
        assertThat(identity.getUnlinkReason()).isEqualTo("APP");

        // Qayta ulash botda — yangi token ishlaydi
        shareContact(612, phone);
        mvc.perform(get("/api/app/me").header("Authorization", bearer(appToken(612))))
            .andExpect(status().isOk());
    }

    @Test
    void auth_resync_studentArchivedInCrm_unlinks() throws Exception {
        String phone = phone();
        Long id = student("Ali", "Karimov", phone, null);
        shareContact(613, phone);
        inTx(() -> studentRepository.findById(id).orElseThrow().setStatus(StudentStatus.ARCHIVED));

        auth(initData(613)).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("app.notLinked"));
        AppIdentity identity = identityRepository.findByTelegramUserId(613L).orElseThrow();
        assertThat(identity.getStatus()).isEqualTo(AppIdentity.Status.UNLINKED);
        assertThat(identity.getUnlinkReason()).isEqualTo("NO_STUDENTS");
    }

    @Test
    void auth_resync_newChildAddedInCrm_visible() throws Exception {
        String phone = phone();
        student("Ali", "Karimov", phone(), phone);
        shareContact(614, phone);
        Long later = student("Vali", "Karimov", phone(), phone);

        auth(initData(614)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.profile.students.length()").value(2))
            .andExpect(jsonPath("$.data.profile.students[1].id").value(later));
    }
}
