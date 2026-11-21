package com.crm.calls;

import com.crm.billing.support.AbstractBillingIT;
import com.crm.entity.Lead;
import com.crm.entity.Student;
import com.crm.entity.User;
import com.crm.entity.enums.StudentStatus;
import com.crm.entity.enums.UserRole;
import com.crm.repository.CallDeviceRepository;
import com.crm.repository.LeadRepository;
import com.crm.repository.PhoneCallRepository;
import com.crm.repository.StudentRepository;
import com.crm.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Adizone Qo'ng'iroqlar" testlari uchun umumiy yordamchilar (docs/design/calls.md). H2 va pgtest'da yuradi.
 * Soat — {@code MutableClock} (2026-09-15 12:00 Toshkent); qo'ng'iroq vaqtlari shunga nisbatan.
 */
abstract class CallsItBase extends AbstractBillingIT {

    /** Testlar orasida (users tozalanmaydi) takrorlanmaydigan raqamlar. */
    private static final AtomicInteger PHONE_SEQ = new AtomicInteger(ThreadLocalRandom.current().nextInt(1_000_000, 8_000_000));
    private static final AtomicInteger USER_SEQ = new AtomicInteger();

    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected UserRepository userRepository;
    @Autowired protected LeadRepository leadRepository;
    @Autowired protected StudentRepository studentRepository;
    @Autowired protected PhoneCallRepository callRepository;
    @Autowired protected CallDeviceRepository deviceRepository;
    @Autowired protected CallsProperties callsProperties;

    protected static String phone() {
        return "+99877" + String.format(Locale.ROOT, "%07d", PHONE_SEQ.incrementAndGet() % 10_000_000);
    }

    protected User staff(UserRole role) {
        return staff(role, null);
    }

    protected User staff(UserRole role, String phone) {
        String username = "calls-" + role.name().toLowerCase(Locale.ROOT) + "-" + USER_SEQ.incrementAndGet() + "-"
            + System.nanoTime() % 1_000_000;
        return inTx(() -> userRepository.save(User.builder()
            .username(username).password("x").firstName("Xodim" + USER_SEQ.get()).lastName(role.name())
            .phone(phone).role(role).isActive(true).build()));
    }

    protected static RequestPostProcessor as(User u) {
        return user(u.getUsername()).roles(u.getRole().name());
    }

    protected ResultActions registerRaw(User u, String deviceUid) throws Exception {
        return mvc.perform(post("/api/calls/devices/register").with(as(u))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"deviceUid\":\"" + deviceUid + "\",\"model\":\"Pixel 7\",\"androidVersion\":\"14\","
                + "\"appVersion\":\"1.0.0\"}"));
    }

    /** Ro'yxatdan o'tkazadi va qurilma tokenini qaytaradi. */
    protected String register(User u) throws Exception {
        return register(u, "uid-" + u.getId() + "-" + System.nanoTime());
    }

    protected String register(User u, String deviceUid) throws Exception {
        String body = registerRaw(u, deviceUid).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.deviceToken");
    }

    protected static MockHttpServletRequestBuilder device(MockHttpServletRequestBuilder b, String token) {
        return b.header(CallDeviceFilter.HEADER, token);
    }

    /** Qo'ng'iroq elementi: {@code minutesAgo} — MutableClock "hozir"idan oldin. */
    protected Map<String, Object> item(String key, String direction, String phone, long minutesAgo, int durationSec) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("direction", direction);
        m.put("phone", phone);
        m.put("startedAt", Instant.from(clock.instant()).minusSeconds(minutesAgo * 60).toString());
        m.put("durationSec", durationSec);
        return m;
    }

    protected ResultActions sync(String token, List<Map<String, Object>> items) throws Exception {
        return mvc.perform(device(post("/api/calls/app/sync"), token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("calls", items))));
    }

    @SafeVarargs
    protected final String syncOk(String token, Map<String, Object>... items) throws Exception {
        return sync(token, new ArrayList<>(List.of(items))).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    protected Long callId(String syncBody, int index) {
        return ((Number) JsonPath.read(syncBody, "$.data.results[" + index + "].callId")).longValue();
    }

    protected Long student(String first, String phone) {
        return inTx(() -> studentRepository.save(Student.builder()
            .firstName(first).lastName("Karimov").phone(phone).status(StudentStatus.ACTIVE).build()).getId());
    }

    protected Long lead(String name, String phone, User owner) {
        return inTx(() -> leadRepository.save(Lead.builder()
            .fullName(name).phone(phone).source("WEBSITE").status("NEW").converted(false)
            .assignedUser(owner != null ? userRepository.findById(owner.getId()).orElseThrow() : null)
            .build()).getId());
    }

    protected long count(String table) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return n == null ? 0 : n;
    }
}
