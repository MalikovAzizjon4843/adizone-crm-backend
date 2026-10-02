package com.crm.util;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;

import java.util.Locale;

/**
 * Ro'yxat qidiruvlari uchun Criteria yordamchilari.
 *
 * <p>Qidiruv matni — {@code LIKE} naqshi emas, oddiy matn: {@code %} va {@code _}
 * qochiriladi ({@code ESCAPE '!'}), aks holda {@code q=%} hamma narsani topardi.
 * Bo'sh {@code q} uchun predikat qurilmaydi — {@code (:p IS NULL OR ...)} yo'q, ya'ni
 * PostgreSQL'dagi "could not determine data type of parameter" muammosi ham yo'q.
 */
public final class SearchSpecs {

    private static final char ESCAPE = '!';

    private SearchSpecs() {
    }

    /** {@code null} — qidiruv yo'q (trim'dan keyin bo'sh). */
    public static String normalize(String q) {
        if (q == null) {
            return null;
        }
        String t = q.trim();
        return t.isEmpty() ? null : t;
    }

    /** {@code %matn%}, kichik harfda, maxsus belgilar qochirilgan. */
    public static String containsPattern(String q) {
        String escaped = q.toLowerCase(Locale.ROOT)
            .replace("!", "!!")
            .replace("%", "!%")
            .replace("_", "!_");
        return "%" + escaped + "%";
    }

    /** {@code lower(expr) LIKE pattern ESCAPE '!'} — ustun VARCHAR bo'lishi shart (bytea emas). */
    public static Predicate containsIgnoreCase(CriteriaBuilder cb, Expression<String> expr, String pattern) {
        return cb.like(cb.lower(expr), pattern, ESCAPE);
    }
}
