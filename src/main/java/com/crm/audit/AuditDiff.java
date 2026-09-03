package com.crm.audit;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

/**
 * Ikki obyektni maydonma-maydon solishtirib {@link AuditContext} ga yozadi.
 *
 * <p>Faqat HAQIQATAN o'zgargan maydonlar qayd qilinadi. Maxfiy maydonlar
 * qiymati hech qachon logga tushmaydi — {@code "***"} bilan almashtiriladi.
 */
@Slf4j
public final class AuditDiff {

    /** Qiymati hech qachon yozilmaydigan maydonlar (nom bo'yicha, registrga bog'liq emas). */
    private static final Set<String> SECRET_FIELDS = Set.of(
        "password", "passwordhash", "newpassword", "oldpassword", "temporarypassword",
        "jwt", "token", "accesstoken", "refreshtoken", "secret", "apikey", "otp");

    private static final String MASK = "***";

    /** Solishtirishga arzimaydigan texnik maydonlar. */
    private static final Set<String> SKIPPED_FIELDS = Set.of(
        "id", "uuid", "createdat", "updatedat", "serialversionuid");

    private AuditDiff() {
    }

    /**
     * Bir xil turdagi ikki obyektni solishtiradi va farqlarni AuditContext ga qo'shadi.
     * Faqat oddiy (skalyar) maydonlar solishtiriladi — kolleksiyalar va boshqa
     * entity'lar lazy proxy bo'lishi mumkin, ularga tegilmaydi.
     */
    public static void compare(Object before, Object after) {
        if (before == null || after == null || !before.getClass().equals(after.getClass())) {
            return;
        }
        for (Class<?> c = before.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
                    continue;
                }
                if (SKIPPED_FIELDS.contains(f.getName().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                if (!isScalar(f.getType())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    AuditContext.change(f.getName(), f.get(before), f.get(after));
                } catch (Exception e) {
                    log.debug("audit diff: {} maydonini o'qib bo'lmadi", f.getName());
                }
            }
        }
    }

    private static boolean isScalar(Class<?> t) {
        return t.isPrimitive()
            || t.isEnum()
            || CharSequence.class.isAssignableFrom(t)
            || Number.class.isAssignableFrom(t)
            || Boolean.class.equals(t)
            || java.time.temporal.Temporal.class.isAssignableFrom(t);
    }

    /** BigDecimal 700000 va 700000.00 ni teng deb sanaydi. */
    static boolean equalValues(Object a, Object b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
            return x.compareTo(y) == 0;
        }
        return a.equals(b);
    }

    static Object maskIfSecret(String field, Object value) {
        if (value == null) {
            return null;
        }
        return isSecret(field) ? MASK : value;
    }

    static boolean isSecret(String field) {
        if (field == null) {
            return false;
        }
        String f = field.toLowerCase(Locale.ROOT).replace("_", "");
        if (SECRET_FIELDS.contains(f)) {
            return true;
        }
        return f.contains("password") || f.contains("token") || f.contains("secret");
    }
}
