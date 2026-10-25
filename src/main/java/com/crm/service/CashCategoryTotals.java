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

    /** Kassa ko'rinishida kitob sotuvi tur bo'yicha (V87, buyurtmachi: "O'quv kitob", "Badiiy kitob"). */
    public static final String BOOK_SALE_STUDY = "BOOK_SALE_STUDY";
    public static final String BOOK_SALE_FICTION = "BOOK_SALE_FICTION";

    /**
     * Kitob sotuvi tushumi tur bo'yicha — sotuv qatorlaridan, kassa yozuvi sanasi bo'yicha ({@code registerId} null —
     * hamma kassa). {@code unhandedOnly} — kunlik topshirish preview'i uchun (topshirilmagan yozuvlar).
     */
    @Transactional(readOnly = true)
    public Map<String, BigDecimal> bookSaleKinds(Long registerId, LocalDate from, LocalDate to, boolean unhandedOnly) {
        StringBuilder sql = new StringBuilder("""
            SELECT l.kind, SUM(l.line_total) FROM book_sale_lines l
            JOIN book_sales s ON s.id = l.sale_id
            JOIN cash_transactions t ON t.id = s.cash_transaction_id
            WHERE 1 = 1""");
        Map<String, Object> params = new HashMap<>();
        if (registerId != null) {
            sql.append(" AND t.cash_register_id = :reg");
            params.put("reg", registerId);
        }
        if (from != null) {
            sql.append(" AND t.transaction_date >= :f");
            params.put("f", Date.valueOf(from));
        }
        if (to != null) {
            sql.append(" AND t.transaction_date <= :t");
            params.put("t", Date.valueOf(to));
        }
        if (unhandedOnly) {
            sql.append(" AND t.handover_id IS NULL AND t.status = 'COMPLETED'");
        }
        sql.append(" GROUP BY l.kind");
        Query q = em.createNativeQuery(sql.toString());
        params.forEach(q::setParameter);
        Map<String, BigDecimal> out = new HashMap<>();
        for (Object o : q.getResultList()) {
            Object[] r = (Object[]) o;
            out.put(String.valueOf(r[0]), dec(r[1]));
        }
        return out;
    }

    /**
     * Kassa ko'rinishi (kassa sahifasi va topshirish preview'i — YAGONA qurilish): kategoriya bo'yicha, kategoriyaning
     * o'z yo'nalishida; noma'lum kod — OTHER; BOOK_SALE — "O'quv kitob" / "Badiiy kitob" qatorlariga bo'linadi.
     */
    public List<com.crm.dto.response.CashCategoryTotalDto> view(List<Total> totals, Map<String, BigDecimal> bookKinds,
                                                                Map<String, com.crm.entity.CashCategory> categories) {
        Map<String, BigDecimal> sums = new java.util.LinkedHashMap<>();
        for (Total t : totals) {
            String code = categories.containsKey(t.category()) ? t.category() : CashCategories.OTHER;
            sums.merge(code, t.amount(), BigDecimal::add);
        }
        List<com.crm.dto.response.CashCategoryTotalDto> out = new ArrayList<>();
        Map<String, Integer> order = new HashMap<>();
        BigDecimal books = sums.remove(CashCategories.BOOK_SALE);
        if (books != null && books.signum() != 0) {
            com.crm.entity.CashCategory c = categories.get(CashCategories.BOOK_SALE);
            BigDecimal study = bookKinds.getOrDefault("STUDY", BigDecimal.ZERO);
            BigDecimal fiction = bookKinds.getOrDefault("FICTION", BigDecimal.ZERO);
            int base = c != null ? c.getSortOrder() * 10 : 0;
            addRow(out, order, BOOK_SALE_STUDY, "O'quv kitob", c, study, base + 1);
            addRow(out, order, BOOK_SALE_FICTION, "Badiiy kitob", c, fiction, base + 2);
            addRow(out, order, CashCategories.BOOK_SALE, c != null ? c.getName() : CashCategories.BOOK_SALE, c,
                books.subtract(study).subtract(fiction), base + 3);
        }
        sums.forEach((code, v) -> {
            com.crm.entity.CashCategory c = categories.get(code);
            if (c != null) {
                addRow(out, order, code, c.getName(), c, v, c.getSortOrder() * 10);
            }
        });
        out.sort(java.util.Comparator.comparing(com.crm.dto.response.CashCategoryTotalDto::getDirection)
            .thenComparing(d -> order.get(d.getCategory())));
        return out;
    }

    private static void addRow(List<com.crm.dto.response.CashCategoryTotalDto> out, Map<String, Integer> order,
                               String code, String name, com.crm.entity.CashCategory c, BigDecimal amount, int sort) {
        if (amount == null || amount.signum() == 0 || c == null) {
            return;
        }
        order.put(code, sort);
        out.add(com.crm.dto.response.CashCategoryTotalDto.builder()
            .category(code).name(name).direction(c.getDirection()).scope(c.getScope()).amount(amount).build());
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
