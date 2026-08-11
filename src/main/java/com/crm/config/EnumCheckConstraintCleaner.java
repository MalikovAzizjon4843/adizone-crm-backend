package com.crm.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Hibernate {@code @Enumerated(STRING)} ustunlar uchun CHECK constraint yaratadi,
 * lekin {@code ddl-auto: update} ularni hech qachon yangilamaydi. Enum'ga yangi qiymat
 * qo'shilsa, baza uni rad etadi (500 xato). Qiymat Java enum darajasida allaqachon
 * tekshirilgani uchun bazadagi bu dublikat cheklov faqat zarar keltiradi.
 *
 * <p>{@link ApplicationRunner} Hibernate schema update'dan KEYIN ishlaydi — shuning uchun
 * tartib to'g'ri bo'ladi. Xato bo'lsa ilova to'xtamaydi, faqat log yoziladi.
 *
 * <p>O'chirish uchun: {@code app.schema.drop-enum-checks: false}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
    name = "app.schema.drop-enum-checks",
    havingValue = "true",
    matchIfMissing = true)
public class EnumCheckConstraintCleaner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    private static final String FIND_ENUM_CHECKS = """
        SELECT conrelid::regclass::text AS tbl, conname
        FROM pg_constraint
        WHERE contype = 'c'
          AND connamespace = 'public'::regnamespace
          AND pg_get_constraintdef(oid) LIKE '%= ANY %ARRAY[%'
        """;

    @Override
    public void run(ApplicationArguments args) {
        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(FIND_ENUM_CHECKS);
        } catch (Exception e) {
            log.error("Enum CHECK constraint ro'yxatini olishda xato", e);
            return;
        }

        if (rows.isEmpty()) {
            log.info("Enum CHECK constraint topilmadi — tozalash shart emas");
            return;
        }

        int count = 0;
        for (Map<String, Object> row : rows) {
            String tbl = String.valueOf(row.get("tbl"));
            String conname = String.valueOf(row.get("conname"));
            try {
                jdbcTemplate.execute("ALTER TABLE " + tbl + " DROP CONSTRAINT \"" + conname + "\"");
                log.info("Enum CHECK o'chirildi: {} ({})", conname, tbl);
                count++;
            } catch (Exception e) {
                log.error("Enum CHECK o'chirib bo'lmadi: {} ({})", conname, tbl, e);
            }
        }
        log.info("Enum CHECK tozalash yakunlandi: {} ta o'chirildi", count);
    }
}
