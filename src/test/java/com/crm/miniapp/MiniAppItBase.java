package com.crm.miniapp;

import com.crm.billing.support.AbstractBillingIT;
import com.crm.billing.support.RecordingTelegramBotApi;
import com.crm.entity.Parent;
import com.crm.entity.Setting;
import com.crm.repository.SettingRepository;
import com.crm.service.CenterSettingsService;
import com.crm.entity.Student;
import com.crm.entity.StudentParent;
import com.crm.entity.enums.StudentStatus;
import com.crm.repository.AppIdentityRepository;
import com.crm.repository.AppIdentityStudentRepository;
import com.crm.repository.ParentRepository;
import com.crm.repository.StudentParentRepository;
import com.crm.repository.StudentRepository;
import com.crm.telegram.TelegramInitDataValidator;
import com.crm.telegram.TelegramProperties;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Telegram bot va Mini App testlari uchun umumiy yordamchilar (docs/design/telegram-platform.md §8):
 * initData test bot tokeni bilan HAQIQIY HMAC bilan yaratiladi, bot javoblari
 * {@link RecordingTelegramBotApi} da.
 */
abstract class MiniAppItBase extends AbstractBillingIT {

    static final String WEBHOOK_SECRET = "test-webhook-secret-0123456789abcdef";
    private static final AtomicLong UPDATE_ID = new AtomicLong(1_000);
    private static final AtomicInteger PHONE_SEQ = new AtomicInteger();

    @Autowired
    protected RecordingTelegramBotApi botApi;
    @Autowired
    protected TelegramProperties telegramProperties;
    @Autowired
    protected StudentRepository studentRepository;
    @Autowired
    protected ParentRepository parentRepository;
    @Autowired
    protected StudentParentRepository studentParentRepository;
    @Autowired
    protected AppIdentityRepository identityRepository;
    @Autowired
    protected AppIdentityStudentRepository identityStudentRepository;

    @Autowired
    protected SettingRepository settingRepository;
    @Autowired
    protected CenterSettingsService centerSettings;

    static final String SUPPORT_PHONE = "+998 77 337 32 33";
    static final String ADDRESS = "Toshkent sh., Chilonzor t., Novza MFY, Ye mavzesi, 10-uy";

    @BeforeEach
    void resetBot() {
        botApi.reset();
        // V63 qiymatlari (H2 da migratsiya yo'q; boshqa testlar o'zgartirgan bo'lishi mumkin)
        inTx(() -> Map.of("supportPhone", SUPPORT_PHONE, "address", ADDRESS).forEach((field, value) -> {
            String key = CenterSettingsService.PREFIX + field;
            Setting s = settingRepository.findBySettingKey(key)
                .orElseGet(() -> Setting.builder().settingKey(key).build());
            s.setSettingValue(value);
            settingRepository.save(s);
        }));
        centerSettings.evict();
    }

    // ── initData ─────────────────────────────────────────────────────────

    protected String initData(long telegramUserId) {
        return initData(telegramUserId, clock.instant().getEpochSecond());
    }

    protected String initData(long telegramUserId, long authDate) {
        Map<String, String> fields = new TreeMap<>();
        fields.put("auth_date", String.valueOf(authDate));
        fields.put("query_id", "AAHdF6IQAAAAAN0XohDhrOrc");
        fields.put("user", "{\"id\":" + telegramUserId
            + ",\"first_name\":\"Dilnoza\",\"last_name\":\"K\",\"username\":\"dilnoza_k\",\"language_code\":\"uz\"}");
        return signed(fields);
    }

    /** Maydonlar → HMAC bilan imzolangan query satri (Telegram WebApp shakli). */
    protected String signed(Map<String, String> fields) {
        String dcs = new TreeMap<>(fields).entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .collect(Collectors.joining("\n"));
        String hash = TelegramInitDataValidator.sign(telegramProperties.getBotToken(), dcs);
        return encode(fields) + "&hash=" + hash;
    }

    protected static String encode(Map<String, String> fields) {
        return fields.entrySet().stream()
            .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));
    }

    protected ResultActions auth(String initData) throws Exception {
        return mvc.perform(post("/api/app/auth").contentType(MediaType.APPLICATION_JSON)
            .content("{\"initData\":" + json(initData) + "}"));
    }

    /** Bog'langan foydalanuvchi uchun app JWT. */
    protected String appToken(long telegramUserId) throws Exception {
        String body = auth(initData(telegramUserId)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.token");
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }

    // ── Webhook ──────────────────────────────────────────────────────────

    protected ResultActions webhook(String updateJson) throws Exception {
        return mvc.perform(post("/api/telegram/webhook")
            .header("X-Telegram-Bot-Api-Secret-Token", WEBHOOK_SECRET)
            .contentType(MediaType.APPLICATION_JSON)
            .content(updateJson));
    }

    protected static long nextUpdateId() {
        return UPDATE_ID.incrementAndGet();
    }

    protected static String textUpdate(long updateId, long telegramUserId, String text) {
        return "{\"update_id\":" + updateId + ",\"message\":{\"message_id\":1,"
            + "\"from\":{\"id\":" + telegramUserId + ",\"is_bot\":false,\"first_name\":\"Dilnoza\",\"username\":\"dilnoza_k\"},"
            + "\"chat\":{\"id\":" + telegramUserId + ",\"type\":\"private\"},"
            + "\"date\":1758000000,\"text\":" + json(text) + "}}";
    }

    protected static String contactUpdate(long updateId, long fromId, Long contactUserId, String phone) {
        return "{\"update_id\":" + updateId + ",\"message\":{\"message_id\":2,"
            + "\"from\":{\"id\":" + fromId + ",\"is_bot\":false,\"first_name\":\"Dilnoza\"},"
            + "\"chat\":{\"id\":" + fromId + ",\"type\":\"private\"},\"date\":1758000000,"
            + "\"contact\":{\"phone_number\":" + json(phone) + ",\"first_name\":\"Dilnoza\""
            + (contactUserId != null ? ",\"user_id\":" + contactUserId : "") + "}}}";
    }

    /** Botga o'z raqamini yuborish (Telegram kontakt formatida — plyussiz). */
    protected void shareContact(long telegramUserId, String canonicalPhone) throws Exception {
        webhook(contactUpdate(nextUpdateId(), telegramUserId, telegramUserId, canonicalPhone.substring(1)))
            .andExpect(status().isOk());
    }

    // ── Ma'lumot ─────────────────────────────────────────────────────────

    /** Testlar orasida takrorlanmaydigan kanonik raqam. */
    protected static String phone() {
        return "+99893" + String.format("%07d", PHONE_SEQ.incrementAndGet() * 7 % 10_000_000);
    }

    protected Long student(String first, String last, String phone, String parentPhone) {
        return inTx(() -> studentRepository.save(Student.builder()
            .firstName(first)
            .lastName(last)
            .phone(phone)
            .parentPhone(parentPhone)
            .status(StudentStatus.ACTIVE)
            .build()).getId());
    }

    protected Long parentOf(String phone, Long... studentIds) {
        return inTx(() -> {
            Parent parent = parentRepository.save(Parent.builder().fullName("Ota-ona").phone(phone).build());
            for (Long id : studentIds) {
                studentParentRepository.save(StudentParent.builder()
                    .student(studentRepository.findById(id).orElseThrow())
                    .parent(parent)
                    .relation("MOTHER")
                    .build());
            }
            return parent.getId();
        });
    }

    // ── Bosqich 3 yordamchilari: xodimlar, o'qituvchi rejimi, outbox ─────

    @Autowired
    protected com.crm.repository.UserRepository userRepository;
    @Autowired
    protected com.crm.repository.TeacherRepository teacherRepository;
    @Autowired
    protected com.crm.repository.TelegramOutboxRepository outboxRepository;
    @Autowired
    protected com.crm.telegram.TelegramOutboxWorker outboxWorker;

    private static final AtomicInteger USER_SEQ = new AtomicInteger();

    /** Faol xodim (login noyob); {@code phone} — o'qituvchi rejimini moslash uchun (null bo'lishi mumkin). */
    protected com.crm.entity.User staff(com.crm.entity.enums.UserRole role, String phone) {
        String username = "ma-" + role.name().toLowerCase(java.util.Locale.ROOT) + "-" + USER_SEQ.incrementAndGet()
            + "-" + System.nanoTime() % 100_000;
        return inTx(() -> userRepository.save(com.crm.entity.User.builder()
            .username(username).password("x").firstName("Xodim" + USER_SEQ.get()).lastName(role.name())
            .phone(phone).role(role).isActive(true).build()));
    }

    /** TEACHER user + bog'langan o'qituvchi profili; o'qituvchi id si. */
    protected Long teacherProfile(com.crm.entity.User user) {
        return inTx(() -> {
            com.crm.entity.Teacher t = teacherRepository.findById(fixtures.teacher()).orElseThrow();
            t.setUser(userRepository.findById(user.getId()).orElseThrow());
            t.setFirstName("Madina");
            t.setLastName("Rahimova");
            return teacherRepository.save(t).getId();
        });
    }

    /** MockMvc so'rovi xodim nomidan (admin zanjiri, JWT filtrisiz). */
    protected static org.springframework.test.web.servlet.request.RequestPostProcessor as(com.crm.entity.User u) {
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
            .user(u.getUsername()).roles(u.getRole().name());
    }

    protected java.util.List<com.crm.entity.TelegramOutbox> outboxFor(long chatId) {
        return inTx(() -> outboxRepository.findByChatIdOrderByIdAsc(chatId));
    }

    protected static String json(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
