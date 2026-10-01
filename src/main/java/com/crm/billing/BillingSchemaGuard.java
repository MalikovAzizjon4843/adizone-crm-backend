package com.crm.billing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Startup tekshiruvi — docs/design/billing-v2.md §10.1.
 *
 * <p>Billing to'g'riligi uchta DB obyektiga tayanadi: chek sequence'i,
 * {@code billing_periods} dagi UNIQUE (I4) va {@code payments.idempotency_key}
 * UNIQUE (§7.3). Ularni {@code V52__billing_v2.sql} yaratadi (qo'lda, Flyway yo'q).
 * Birortasi yo'q bo'lsa — billing yozish o'chiriladi (503) va ERROR log, aks
 * holda tizim takror davr yoki takror chek bilan jimgina ishlab ketardi.
 */
@Slf4j
@Component
@Order(10)
@RequiredArgsConstructor
public class BillingSchemaGuard implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final BillingProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        List<String> missing = findMissing();
        if (missing.isEmpty()) {
            log.info("Billing v2 sxemasi tayyor (sequence, billing_periods UNIQUE, idempotency_key UNIQUE)");
            return;
        }
        properties.setEnabled(false);
        log.error("Billing v2 sxemasi to'liq emas: {} — app.billing.enabled majburan false. "
            + "db/migration/V52__billing_v2.sql ni bajaring.", missing);
    }

    public List<String> findMissing() {
        List<String> missing = new ArrayList<>();
        if (!sequenceExists(ReceiptNumberService.SEQUENCE)) {
            missing.add("sequence " + ReceiptNumberService.SEQUENCE);
        }
        if (!uniqueExists("billing_periods", "period_start")) {
            missing.add("billing_periods UNIQUE (student_group_id, period_start)");
        }
        if (!uniqueExists("payments", "idempotency_key")) {
            missing.add("payments.idempotency_key UNIQUE");
        }
        return missing;
    }

    private boolean sequenceExists(String name) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.sequences WHERE LOWER(sequence_name) = ?",
            Integer.class, name.toLowerCase());
        return count != null && count > 0;
    }

    /** {@code table} da {@code column} ni o'z ichiga olgan UNIQUE constraint bormi. */
    private boolean uniqueExists(String table, String column) {
        Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
              ON tc.constraint_name = kcu.constraint_name
             AND tc.table_name = kcu.table_name
            WHERE tc.constraint_type = 'UNIQUE'
              AND LOWER(tc.table_name) = ?
              AND LOWER(kcu.column_name) = ?
            """, Integer.class, table.toLowerCase(), column.toLowerCase());
        return count != null && count > 0;
    }
}
