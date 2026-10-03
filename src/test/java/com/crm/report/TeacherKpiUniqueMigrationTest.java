package com.crm.report;

import com.crm.billing.support.AbstractBillingIT;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prod holati: teacher_kpi_monthly ni Hibernate yaratgan, UNIQUE (teacher_id, month_start) yo'q, dublikat bor.
 * V74 (va V72 ham) dublikatni tozalab UNIQUE qo'shadi; qayta bajarilsa o'zgarish yo'q. Faqat PostgreSQL.
 */
class TeacherKpiUniqueMigrationTest extends AbstractBillingIT {

    @Autowired JdbcTemplate jdbc;

    private void insert(Long teacher, String month, String computedAt) {
        jdbc.update("""
            INSERT INTO teacher_kpi_monthly (teacher_id, month_start, attendance_present, attendance_total,
                periods_decided, periods_paid, periods_on_time, periods_pending, open_at_end, graduated, churned,
                insufficient_data, group_count, student_count, source, computed_at)
            VALUES (?, CAST(? AS DATE), 0, 0, 0, 0, 0, 0, 0, 0, 0, true, 0, 0, 'JOB', CAST(? AS TIMESTAMP))
            """, teacher, month, computedAt);
    }

    private boolean uniqueExists() {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM pg_index i
                            WHERE i.indrelid = 'teacher_kpi_monthly'::regclass AND i.indisunique AND i.indpred IS NULL
                              AND (SELECT array_agg(a.attname::text ORDER BY a.attname) FROM pg_attribute a
                                    WHERE a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey))
                                  = ARRAY['month_start', 'teacher_id'])
            """, Boolean.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"V74__teacher_kpi_monthly_unique.sql", "V72__teacher_kpi_monthly.sql"})
    void hibernateTableWithoutUnique_duplicatesRemoved_uniqueAdded_idempotent(String script) {
        Assumptions.assumeTrue(isPostgres(), "V72/V74 — PostgreSQL skripti (pgtest)");
        // pgtest init V72/V74 ni allaqachon bajargan — prod holatiga qaytariladi
        jdbc.execute("DROP INDEX IF EXISTS ux_teacher_kpi_monthly_teacher_month");
        assertThat(uniqueExists()).isFalse();

        Long a = fixtures.teacher();
        Long b = fixtures.teacher();
        insert(a, "2026-09-01", "2026-10-01 01:00:00");
        insert(a, "2026-09-01", "2026-10-02 12:00:00");   // eng yangisi — qoladi
        insert(a, "2026-09-01", "2026-09-30 23:00:00");
        insert(b, "2026-09-01", "2026-10-01 01:00:00");   // dublikatsiz
        Long keep = jdbc.queryForObject("SELECT id FROM teacher_kpi_monthly WHERE teacher_id = ?"
            + " AND computed_at = TIMESTAMP '2026-10-02 12:00:00'", Long.class, a);

        runScript("db/migration/" + script);

        assertThat(uniqueExists()).isTrue();
        List<Long> left = jdbc.queryForList("SELECT id FROM teacher_kpi_monthly WHERE teacher_id = ?", Long.class, a);
        assertThat(left).containsExactly(keep);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM teacher_kpi_monthly", Integer.class)).isEqualTo(2);

        // Qayta: hech narsa o'zgarmaydi, UNIQUE qoladi (nom bo'yicha bitta)
        runScript("db/migration/" + script);
        assertThat(uniqueExists()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_indexes WHERE tablename = 'teacher_kpi_monthly'"
            + " AND indexname = 'ux_teacher_kpi_monthly_teacher_month'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM teacher_kpi_monthly", Integer.class)).isEqualTo(2);
    }

    private boolean isPostgres() {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) c ->
            c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgres")));
    }

    private void runScript(String path) {
        jdbc.execute((ConnectionCallback<Void>) c -> {
            ScriptUtils.executeSqlScript(c, new EncodedResource(new ClassPathResource(path)),
                false, false, ScriptUtils.DEFAULT_COMMENT_PREFIX, ScriptUtils.EOF_STATEMENT_SEPARATOR,
                ScriptUtils.DEFAULT_BLOCK_COMMENT_START_DELIMITER, ScriptUtils.DEFAULT_BLOCK_COMMENT_END_DELIMITER);
            return null;
        });
    }
}
