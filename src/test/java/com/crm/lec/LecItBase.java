package com.crm.lec;

import com.crm.billing.support.AbstractBillingIT;
import com.crm.entity.GroupScheduleDay;
import com.crm.entity.Setting;
import com.crm.entity.Teacher;
import com.crm.entity.User;
import com.crm.entity.enums.UserRole;
import com.crm.exception.CodedException;
import com.crm.repository.GroupRepository;
import com.crm.repository.GroupScheduleDayRepository;
import com.crm.repository.SettingRepository;
import com.crm.repository.TeacherRepository;
import com.crm.repository.UserRepository;
import com.crm.service.CenterSettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

/**
 * Ta'tillar, o'rinbosarlar, imtihon to'lovi, shartnoma (docs/design/leaves-exams-contracts.md §8)
 * testlari uchun umumiy yordamchilar. {@code users} va {@code settings} {@code fixtures.wipe()} da
 * tozalanmaydi — har test noyob login yaratadi, rekvizitlarni esa {@link #seedCenter()} tiklaydi.
 */
abstract class LecItBase extends AbstractBillingIT {

    private static final AtomicInteger SEQ = new AtomicInteger();

    /** V63 dagi boshlang'ich qiymatlar bilan bir xil. */
    static final Map<String, String> CENTER = new LinkedHashMap<>();

    static {
        CENTER.put("legalName", "\"ADIZONE LC\" MChJ");
        CENTER.put("legalNameRu", "ООО «ADIZONE LC»");
        CENTER.put("shortName", "Adizone");
        CENTER.put("inn", "311626069");
        CENTER.put("address", "Toshkent sh., Chilonzor t., Novza MFY, Ye mavzesi, 10-uy");
        CENTER.put("phone", "+998 90 045 55 17");
        CENTER.put("bankName", "ОПЕРУ АКБ «Капитал Банк»");
        CENTER.put("bankMfo", "00974");
        CENTER.put("bankAccount", "20208000007147330001");
        CENTER.put("directorName", "Adizov Oqilbek Oybek o'g'li");
        CENTER.put("contractCity", "Toshkent shahri");
        CENTER.put("licenseInfo", "Xabarnoma tasdiqnomasi №1180460 (reestr X-1743276)");
        CENTER.put("supportPhone", "+998 77 337 32 33");
    }

    @Autowired protected UserRepository userRepository;
    @Autowired protected TeacherRepository teacherRepository;
    @Autowired protected GroupRepository groupRepository;
    @Autowired protected GroupScheduleDayRepository scheduleDayRepository;
    @Autowired protected SettingRepository settingRepository;
    @Autowired protected CenterSettingsService centerSettings;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected ObjectMapper objectMapper;

    protected User newUser(UserRole role) {
        String username = "lec-" + role.name().toLowerCase(Locale.ROOT) + "-" + SEQ.incrementAndGet()
            + "-" + System.nanoTime() % 100_000;
        return inTx(() -> userRepository.save(User.builder()
            .username(username)
            .password("x")
            .firstName("Xodim" + SEQ.get())
            .lastName(role.name())
            .role(role)
            .isActive(true)
            .build()));
    }

    /** TEACHER foydalanuvchi + bog'langan o'qituvchi profili. */
    protected record TeacherUser(User user, Long teacherId) {
    }

    protected TeacherUser newTeacher() {
        User u = newUser(UserRole.TEACHER);
        Long teacherId = inTx(() -> {
            Teacher t = teacherRepository.findById(fixtures.teacher()).orElseThrow();
            t.setUser(userRepository.findById(u.getId()).orElseThrow());
            return teacherRepository.save(t).getId();
        });
        return new TeacherUser(u, teacherId);
    }

    /** Guruh jadvali: kunlar (MONDAY…) va vaqt. */
    protected void schedule(Long groupId, String start, String end, String... days) {
        inTx(() -> {
            for (String day : days) {
                scheduleDayRepository.save(GroupScheduleDay.builder()
                    .group(groupRepository.findById(groupId).orElseThrow())
                    .dayOfWeek(day)
                    .startTime(start)
                    .endTime(end)
                    .build());
            }
        });
    }

    protected static RequestPostProcessor as(User u) {
        return user(u.getUsername()).roles(u.getRole().name());
    }

    protected static void loginAs(User u) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            u.getUsername(), null, List.of(new SimpleGrantedAuthority("ROLE_" + u.getRole().name()))));
    }

    /** Rekvizitlarni V63 qiymatlariga qaytaradi (boshqa testlar o'zgartirgan bo'lishi mumkin). */
    protected void seedCenter() {
        inTx(() -> {
            CENTER.forEach((field, value) -> {
                String key = CenterSettingsService.PREFIX + field;
                Setting s = settingRepository.findBySettingKey(key)
                    .orElseGet(() -> Setting.builder().settingKey(key).build());
                s.setSettingValue(value);
                settingRepository.save(s);
            });
        });
        centerSettings.evict();
    }

    protected JsonNode data(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("data");
    }

    protected boolean isPostgres() {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) c ->
            c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgres")));
    }

    protected int awaitAudits(String action, String entityType) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        Integer n = 0;
        while (System.currentTimeMillis() < deadline) {
            n = jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action = ? AND entity_type = ?",
                Integer.class, action, entityType);
            if (n != null && n > 0) {
                break;
            }
            Thread.sleep(50);
        }
        return n == null ? 0 : n;
    }

    static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run)
            .isInstanceOf(CodedException.class)
            .extracting(e -> ((CodedException) e).getCode())
            .isEqualTo(code);
    }

    /** Joriy (haqiqiy) sanadan keyingi shu hafta kuni — davomat "kelajak" tekshiruvi real soat bo'yicha. */
    protected static LocalDate nextOrSame(LocalDate from, java.time.DayOfWeek day) {
        return from.with(java.time.temporal.TemporalAdjusters.nextOrSame(day));
    }
}
