package com.crm.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Shartnoma raqami {@code CTR-YYYY-NNNNN} — har yil 00001 dan (phase5-audit C-01, Q8).
 *
 * <p>Hisoblagich — {@value #TABLE} jadvali, har yilga bitta qator (V59). Raqam olish:
 * <ol>
 *   <li>{@code UPDATE … SET last_value = last_value + 1} — qator qulflanadi, parallel
 *       so'rovlar navbat bilan o'tadi, takror raqam bo'lmaydi;</li>
 *   <li>yil qatori hali yo'q bo'lsa — shu yilning mavjud {@code CTR-YYYY-NNNNN}
 *       raqamlarining eng kattasidan boshlab qo'shiladi (V58 sequence davridagi
 *       raqamlar bilan to'qnashmaslik uchun). Ikki so'rov bir vaqtda qo'shmoqchi
 *       bo'lsa, PRIMARY KEY ikkinchisini rad etadi va u qaytadan UPDATE qiladi.</li>
 * </ol>
 *
 * <p>Alohida tranzaksiyada ({@code REQUIRES_NEW}): qulf shartnoma saqlanguncha
 * ushlab turilmaydi va PostgreSQL'da yiqilgan INSERT tashqi tranzaksiyani buzmaydi.
 * Shartnoma keyin rollback bo'lsa raqam "yeyiladi" — bo'shliq me'yor (chek raqamidagi kabi).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContractNumberService {

    public static final String TABLE = "contract_number_counters";
    private static final int MAX_ATTEMPTS = 3;

    private final JdbcTemplate jdbcTemplate;
    private final PlatformTransactionManager transactionManager;

    public String next(int year) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        for (int attempt = 1; ; attempt++) {
            try {
                Long value = tx.execute(status -> allocate(year));
                return format(year, value);
            } catch (DataIntegrityViolationException e) {
                // Shu yil qatorini boshqa so'rov birinchi qo'shdi — endi UPDATE o'tadi.
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                log.debug("Shartnoma hisoblagichi {} parallel yaratildi, qayta urinish", year);
            }
        }
    }

    private long allocate(int year) {
        int updated = jdbcTemplate.update(
            "UPDATE " + TABLE + " SET last_value = last_value + 1 WHERE contract_year = ?", year);
        if (updated == 0) {
            long start = maxExistingNumber(year) + 1;
            jdbcTemplate.update(
                "INSERT INTO " + TABLE + " (contract_year, last_value) VALUES (?, ?)", year, start);
            return start;
        }
        Long value = jdbcTemplate.queryForObject(
            "SELECT last_value FROM " + TABLE + " WHERE contract_year = ?", Long.class, year);
        if (value == null) {
            throw new IllegalStateException("Shartnoma hisoblagichi topilmadi: " + year);
        }
        return value;
    }

    /** Shu yilning eng katta {@code CTR-YYYY-NNNNN} raqami (yo'q bo'lsa 0). */
    private long maxExistingNumber(int year) {
        String prefix = "CTR-" + year + "-";
        Long max = jdbcTemplate.queryForObject(
            "SELECT MAX(CAST(SUBSTRING(contract_number, 10) AS BIGINT)) FROM contracts"
                + " WHERE contract_number LIKE ? AND LENGTH(contract_number) > 9",
            Long.class, prefix + "%");
        return max != null ? max : 0L;
    }

    static String format(int year, long value) {
        return "CTR-" + year + "-" + String.format("%05d", value);
    }
}
