package com.crm.entity.enums;

import java.util.Locale;
import java.util.Map;

/**
 * {@link PaymentMethod} uchun yordamchi metodlar.
 *
 * <p>Birlashtirishdan oldin loyihada uchta turlicha ro'yxat bor edi
 * (PaymentMethod, CashPaymentMethod, Payroll.paymentMethod matni). Eski nomlar
 * bazada va frontend so'rovlarida hali uchraydi, shuning uchun ular yangi
 * qiymatlarga moslashtiriladi.
 */
public final class PaymentMethods {

    /** Eski nom -> yangi qiymat. */
    private static final Map<String, PaymentMethod> LEGACY_ALIASES = Map.of(
        "PLASTIC", PaymentMethod.CARD,
        "ONLINE", PaymentMethod.OTHER,
        "BANK_TRANSFER", PaymentMethod.BANK
    );

    private PaymentMethods() {
    }

    /** Matnni PaymentMethod ga o'giradi; tanilmasa null. Eski nomlarni ham tushunadi. */
    public static PaymentMethod parseOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        PaymentMethod alias = LEGACY_ALIASES.get(key);
        if (alias != null) {
            return alias;
        }
        try {
            return PaymentMethod.valueOf(key);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Matnni PaymentMethod ga o'giradi; tanilmasa berilgan qiymatni qaytaradi. */
    public static PaymentMethod parseOrDefault(String raw, PaymentMethod fallback) {
        PaymentMethod parsed = parseOrNull(raw);
        return parsed != null ? parsed : fallback;
    }
}
