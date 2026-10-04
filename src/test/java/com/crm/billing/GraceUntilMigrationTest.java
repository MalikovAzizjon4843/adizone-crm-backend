package com.crm.billing;

import com.crm.billing.support.AbstractBillingIT;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.time.LocalDate;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** V75 (QAROR 2, billing-v2 §14.1): eski grace (+3) bilan yozilgan grace_until → due_date; idempotent. Faqat PostgreSQL. */
class GraceUntilMigrationTest extends AbstractBillingIT {

    @Autowired AccrualService accrual;
    @Autowired JdbcTemplate jdbc;

    @Test
    void oldGraceRows_setToDueDate_dueNullFilledFromPeriodStart_idempotent() {
        Assumptions.assumeTrue(isPostgres(), "V75 — PostgreSQL skripti (pgtest)");
        Long sg = fixtures.enrollment(fixtures.student(), fixtures.group(fixtures.course(700_000)))
            .start(d("15.08.2026")).save();
        accrual.accrueUpTo(sg, d("15.09.2026"));                    // 15.08 va 15.09 davrlari, grace_until = due
        // Eski holat: grace 3 bilan yozilgan; bittasida due_date hali to'ldirilmagan
        jdbc.update("UPDATE billing_periods SET grace_until = due_date + 3 WHERE student_group_id = ?", sg);
        jdbc.update("UPDATE billing_periods SET due_date = NULL, grace_until = DATE '2026-09-18'"
            + " WHERE student_group_id = ? AND period_start = DATE '2026-09-15'", sg);

        runScript("db/migration/V75__grace_until_due_date.sql");

        assertThat(jdbc.queryForObject("SELECT grace_until FROM billing_periods WHERE student_group_id = ?"
            + " AND period_start = DATE '2026-08-15'", LocalDate.class, sg)).isEqualTo(d("15.08.2026"));
        // due_date yo'q edi — PeriodCoverageService qoidasi: due_date = grace_until = period_start
        assertThat(jdbc.queryForObject("SELECT due_date FROM billing_periods WHERE student_group_id = ?"
            + " AND period_start = DATE '2026-09-15'", LocalDate.class, sg)).isEqualTo(d("15.09.2026"));
        assertThat(jdbc.queryForObject("SELECT grace_until FROM billing_periods WHERE student_group_id = ?"
            + " AND period_start = DATE '2026-09-15'", LocalDate.class, sg)).isEqualTo(d("15.09.2026"));
        Integer left = jdbc.queryForObject("SELECT COUNT(*) FROM billing_periods"
            + " WHERE due_date IS NULL OR grace_until IS DISTINCT FROM due_date", Integer.class);
        assertThat(left).isZero();

        runScript("db/migration/V75__grace_until_due_date.sql");     // qayta — o'zgarish yo'q
        assertThat(jdbc.queryForObject("SELECT grace_until FROM billing_periods WHERE student_group_id = ?"
            + " AND period_start = DATE '2026-08-15'", LocalDate.class, sg)).isEqualTo(d("15.08.2026"));
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
