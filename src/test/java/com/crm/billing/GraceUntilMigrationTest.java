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

    /** V76: langar 29.09, oxirgi davr eski qoida bilan 29.09–30.10 → 28.10; 15-sanali va qaytarilgan davrga tegilmaydi. */
    @Test
    void v76_trimsOldRuleLastPeriodEnd_only29to31_notRefunded_idempotent() {
        Assumptions.assumeTrue(isPostgres(), "V76 — PostgreSQL skripti (pgtest)");
        Long a29 = fixtures.enrollment(fixtures.student(), fixtures.group(fixtures.course(700_000)))
            .start(d("29.09.2026")).save();
        Long a15 = fixtures.enrollment(fixtures.student(), fixtures.group(fixtures.course(700_000)))
            .start(d("15.09.2026")).save();
        Long refunded = fixtures.enrollment(fixtures.student(), fixtures.group(fixtures.course(700_000)))
            .start(d("30.09.2026")).save();
        clock.setDate(d("30.09.2026"));
        for (Long sg : new Long[]{a29, a15, refunded}) {
            accrual.accrueUpTo(sg, d("30.09.2026"));
        }
        jdbc.update("UPDATE billing_periods SET period_end = DATE '2026-10-30' WHERE student_group_id IN (?, ?)",
            a29, refunded);
        jdbc.update("UPDATE billing_periods SET status = 'PARTIALLY_REFUNDED' WHERE student_group_id = ?", refunded);

        runScript("db/migration/V76__period_end_anchor_day.sql");
        runScript("db/migration/V76__period_end_anchor_day.sql");      // qayta — o'zgarish yo'q

        assertThat(end(a29)).isEqualTo(d("28.10.2026"));
        assertThat(end(a15)).isEqualTo(d("14.10.2026"));
        assertThat(end(refunded)).isEqualTo(d("30.10.2026"));
    }

    private LocalDate end(Long sg) {
        return jdbc.queryForObject("SELECT period_end FROM billing_periods WHERE student_group_id = ?", LocalDate.class, sg);
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
