package com.crm.entity.enums;

import java.util.Locale;
import java.util.Map;

/**
 * Loyihadagi YAGONA to'lov usuli ro'yxati.
 * Payment, CashTransaction va Payroll shu ro'yxatdan foydalanadi.
 *
 * <p>Kassa chelagi ({@link CashBucket}) ham shu yerda belgilanadi — servislarda
 * qo'lda yozilgan ro'yxat bo'lmasligi uchun.
 */
public enum PaymentMethod {

    CASH("Naqd", "💵", CashBucket.CASH, false),
    CARD("Karta", "💳", CashBucket.NON_CASH, false),
    CLICK("Click", "📱", CashBucket.NON_CASH, true),
    PAYME("Payme", "🔷", CashBucket.NON_CASH, true),
    UZUM("Uzum", "🟠", CashBucket.NON_CASH, true),
    TERMINAL("Terminal", "🖥️", CashBucket.NON_CASH, false),
    BANK("Bank o'tkazmasi", "🏦", CashBucket.NON_CASH, false),
    CASH_AND_CARD("Naqd + Karta", "💵💳", CashBucket.SPLIT, false),
    OTHER("Boshqa", "❓", CashBucket.NON_CASH, false);

    /** Summa kassaning qaysi balansiga tushishi. */
    public enum CashBucket {
        /** To'liq naqd balansga. */
        CASH,
        /** To'liq plastik (naqdsiz) balansga. */
        NON_CASH,
        /** Ikkiga bo'linadi: cashPart -> naqd, cardPart -> plastik. */
        SPLIT
    }

    /** Eski nom -> yangi qiymat. Bazada va eski so'rovlarda hali uchraydi. */
    private static final Map<String, PaymentMethod> LEGACY_ALIASES = Map.of(
        "PLASTIC", CARD,
        "ONLINE", OTHER,
        "BANK_TRANSFER", BANK
    );

    private final String label;
    private final String icon;
    private final CashBucket cashBucket;
    private final boolean online;

    PaymentMethod(String label, String icon, CashBucket cashBucket, boolean online) {
        this.label = label;
        this.icon = icon;
        this.cashBucket = cashBucket;
        this.online = online;
    }

    public String getLabel() {
        return label;
    }

    public String getIcon() {
        return icon;
    }

    public CashBucket getCashBucket() {
        return cashBucket;
    }

    /** Onlayn to'lov tizimimi? Kassada "onlayn qabul qilish" yoqilgan bo'lishi shart. */
    public boolean isOnline() {
        return online;
    }

    /** Matnni qiymatga o'giradi; tanilmasa null. Eski nomlarni ham tushunadi. */
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
            return valueOf(key);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Matnni qiymatga o'giradi; tanilmasa berilgan qiymatni qaytaradi. */
    public static PaymentMethod parseOrDefault(String raw, PaymentMethod fallback) {
        PaymentMethod parsed = parseOrNull(raw);
        return parsed != null ? parsed : fallback;
    }
}
