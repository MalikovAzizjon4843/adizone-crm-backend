package com.crm.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Kassa yozuvlari kategoriya bo'yicha (docs/design/cash-and-books.md §1.3): kirim/chiqim − bekor
 * qilinganlari (REVERSAL asl yozuv kategoriyasini oladi), TRANSFER kirmaydi. Summa — kategoriyaning
 * o'z yo'nalishida (chiqim kategoriyasi uchun musbat = chiqim).
 *
 * <p>{@code category} hali to'ldirilmagan eski yozuv uchun kategoriya docs/ops/cash-category-backfill.sql
 * qoidalari bilan AYNAN bir xil hosil qilinadi — hisobot backfill'ga bog'liq emas.
 *
 * <p>{@code link} — boshqa manbada hisoblanadigan oqim: PAYMENT ({@code payments}), EXAM ({@code examFees}),
 * PAYROLL ({@code payroll}). Moliya hisoboti ularni o'tkazib yuboradi; kassa ko'rinishi esa hisobga oladi.
 * PAYMENT — {@code payment_id} bo'lsa ham, kategoriya STUDENT_PAYMENT bo'lsa ham: billing v2 dan oldingi to'lov
 * kirimida {@code payment_id} yo'q (nomi "O'quvchi to'lovi", to'lovga izoh "To'lov #RCP-…" orqali bog'langan),
 * lekin to'lov {@code payments} da bor — {@code totalIncome} ga kiradi, ikki marta sanalmaydi.
 */
@Service
public class CashCategoryTotals {

    /**
     * {@code %s} — qo'shimcha WHERE shartlari ({@link String#formatted}; shuning uchun LIKE dagi foiz {@code %%}).
     * "Xarajat: X" — eski FinanceService yozuvi nomi, X = 10-belgidan.
     */
    private static final String SQL = """
        SELECT x.d, x.cat, x.link, SUM(x.amt) FROM (
            SELECT y.d AS d, y.amt AS amt, y.cat AS cat,
                   CASE WHEN y.payroll_id IS NOT NULL THEN 'PAYROLL'
                        WHEN y.payment_id IS NOT NULL OR y.cat = 'STUDENT_PAYMENT' THEN 'PAYMENT'
                        WHEN y.exam_id IS NOT NULL THEN 'EXAM'
                        ELSE NULL END AS link
            FROM (
            SELECT s.d AS d, s.amt AS amt, s.payroll_id AS payroll_id, s.payment_id AS payment_id, s.exam_id AS exam_id,
                   COALESCE(s.cat,
                       CASE WHEN s.payment_id IS NOT NULL THEN 'STUDENT_PAYMENT'
                            WHEN s.exam_id IS NOT NULL THEN 'EXAM'
                            WHEN s.payroll_id IS NOT NULL THEN 'SALARY'
                            WHEN s.src_type = 'INCOME' AND s.src_name = 'O''quvchi to''lovi' THEN 'STUDENT_PAYMENT'
                            WHEN s.src_type = 'EXPENSE' AND s.src_name = 'O''quvchiga pul qaytarildi' THEN 'REFUND'
                            WHEN s.src_type = 'EXPENSE' AND s.src_name LIKE 'Xarajat: %%'
                                THEN SUBSTRING(s.src_name FROM 10)
                            WHEN s.src_type = 'INCOME' THEN 'OTHER_INCOME'
                            ELSE 'OTHER' END) AS cat
            FROM (
                SELECT t.transaction_date AS d,
                       CASE WHEN t.type = 'REVERSAL' THEN -t.amount ELSE t.amount END AS amt,
                       CASE WHEN t.type = 'REVERSAL' THEN o.category ELSE t.category END AS cat,
                       CASE WHEN t.type <> 'REVERSAL' THEN t.type
                            WHEN o.type IS NOT NULL THEN o.type
                            WHEN t.payment_id IS NOT NULL OR t.exam_registration_id IS NOT NULL THEN 'INCOME'
                            ELSE 'EXPENSE' END AS src_type,
                       CASE WHEN t.type = 'REVERSAL' THEN o.transaction_name ELSE t.transaction_name END AS src_name,
                       COALESCE(t.payment_id, o.payment_id) AS payment_id,
                       COALESCE(t.exam_registration_id, o.exam_registration_id) AS exam_id,
                       COALESCE(t.payroll_id, o.payroll_id) AS payroll_id
                FROM cash_transactions t
                LEFT JOIN cash_transactions o ON o.id = t.related_tx_id AND t.type = 'REVERSAL'
                WHERE t.type IN ('INCOME', 'EXPENSE', 'REVERSAL') %s
            ) s
            ) y
        ) x
        GROUP BY x.d, x.cat, x.link
        """;

    @PersistenceContext
    private EntityManager em;

    /** Bir kun, bir kategoriya, bir manba bo'yicha yig'indi. */
    public record Row(LocalDate date, String category, String link, BigDecimal amount) {
    }

    /** Kategoriya + manba bo'yicha jami (sanasiz). */
    public record Total(String category, String link, BigDecimal amount) {
    }

    @Transactional(readOnly = true)
    public List<Row> rows(Long registerId, LocalDate from, LocalDate to) {
        StringBuilder where = new StringBuilder();
        Map<String, Object> params = new HashMap<>();
        if (registerId != null) {
            where.append(" AND t.cash_register_id = :reg");
            params.put("reg", registerId);
        }
        if (from != null) {
            where.append(" AND t.transaction_date >= :f");
            params.put("f", Date.valueOf(from));
        }
        if (to != null) {
            where.append(" AND t.transaction_date <= :t");
            params.put("t", Date.valueOf(to));
        }
        return query(where.toString(), params);
    }

    @Transactional(readOnly = true)
    public List<Total> totals(Long registerId, LocalDate from, LocalDate to) {
        return merge(rows(registerId, from, to));
    }

    /** Kassaning hali topshirilmagan (bajarilgan) yozuvlari — kunlik topshirish preview'i uchun. */
    @Transactional(readOnly = true)
    public List<Total> unhandedTotals(Long registerId) {
        return merge(query(" AND t.cash_register_id = :reg AND t.handover_id IS NULL AND t.status = 'COMPLETED'",
            Map.of("reg", registerId)));
    }

    private List<Row> query(String where, Map<String, Object> params) {
        Query q = em.createNativeQuery(SQL.formatted(where));
        params.forEach(q::setParameter);
        List<Row> out = new ArrayList<>();
        for (Object o : q.getResultList()) {
            Object[] r = (Object[]) o;
            out.add(new Row(toDate(r[0]), (String) r[1], (String) r[2], dec(r[3])));
        }
        return out;
    }

    private static List<Total> merge(List<Row> rows) {
        Map<String, Total> merged = new HashMap<>();
        for (Row r : rows) {
            String key = r.category() + "|" + r.link();
            Total prev = merged.get(key);
            merged.put(key, new Total(r.category(), r.link(),
                prev == null ? r.amount() : prev.amount().add(r.amount())));
        }
        return new ArrayList<>(merged.values());
    }

    private static LocalDate toDate(Object v) {
        if (v instanceof LocalDate d) {
            return d;
        }
        if (v instanceof Date d) {
            return d.toLocalDate();
        }
        return v != null ? LocalDate.parse(v.toString()) : null;
    }

    private static BigDecimal dec(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        return v instanceof BigDecimal b ? b : new BigDecimal(v.toString());
    }
}
