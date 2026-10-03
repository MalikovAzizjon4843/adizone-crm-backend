package com.crm.entity.enums;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Kassa hisobotlaridagi to'lov usuli guruhi: balans va tranzaksiyalar shu kesimda alohida.
 * {@link PaymentMethod} → kanal: CLICK/PAYME/UZUM → ONLINE, qolganlari o'z nomi bilan.
 *
 * <p>{@link PaymentMethod#CASH_AND_CARD} ikkiga bo'linadi — {@code cashPart → CASH},
 * {@code cardPart → CARD} ({@code CashRegisterService.bucketAmounts} bilan aynan bir xil).
 * Qismlari saqlanmagan eski CASH_AND_CARD yozuvi va usuli yo'q yozuv to'liq CASH ga — saqlangan
 * {@code cash_balance} ham shunday yozilgan.
 *
 * <p>Bazada saqlanmaydi: har doim {@code payment_method} + {@code cash_part/card_part} dan hisoblanadi.
 */
public enum PaymentChannel {

    CASH("Naqd"),
    CARD("Karta"),
    TERMINAL("Terminal"),
    ONLINE("Onlayn (Click, Payme, Uzum)"),
    BANK("Bank o'tkazmasi"),
    OTHER("Boshqa");

    private final String label;

    PaymentChannel(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** Naqd kassa chelagi ({@code cash_balance}); qolgan hammasi — {@code plastic_balance}. */
    public boolean isCash() {
        return this == CASH;
    }

    /** Bo'linmaydigan usul kanali; CASH_AND_CARD uchun {@link #split} ishlatiladi. */
    public static PaymentChannel of(PaymentMethod method) {
        if (method == null) {
            return CASH;
        }
        return switch (method) {
            case CASH, CASH_AND_CARD -> CASH;
            case CARD -> CARD;
            case TERMINAL -> TERMINAL;
            case CLICK, PAYME, UZUM -> ONLINE;
            case BANK -> BANK;
            case OTHER -> OTHER;
        };
    }

    /** Bitta yozuv summasining kanallar bo'yicha taqsimoti (musbat qiymatlar). */
    public static Map<PaymentChannel, BigDecimal> split(PaymentMethod method, BigDecimal amount,
                                                        BigDecimal cashPart, BigDecimal cardPart) {
        Map<PaymentChannel, BigDecimal> out = new EnumMap<>(PaymentChannel.class);
        BigDecimal value = amount != null ? amount : BigDecimal.ZERO;
        if (method == PaymentMethod.CASH_AND_CARD && cashPart != null && cardPart != null) {
            if (cashPart.signum() != 0) {
                out.put(CASH, cashPart);
            }
            if (cardPart.signum() != 0) {
                out.put(CARD, cardPart);
            }
            return out;
        }
        out.put(of(method), value);
        return out;
    }

    /** Shu kanalga tushishi mumkin bo'lgan usullar (filtr uchun); CASH_AND_CARD — CASH va CARD da. */
    public List<PaymentMethod> methods() {
        return Arrays.stream(PaymentMethod.values())
            .filter(m -> of(m) == this || (m == PaymentMethod.CASH_AND_CARD && this == CARD))
            .toList();
    }

    /** Matn → kanal; tanilmasa null. Usul nomi ham qabul qilinadi (CLICK → ONLINE). */
    public static PaymentChannel parseOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.trim().toUpperCase(Locale.ROOT);
        try {
            return valueOf(key);
        } catch (IllegalArgumentException e) {
            PaymentMethod m = PaymentMethod.parseOrNull(key);
            return m != null && m != PaymentMethod.CASH_AND_CARD ? of(m) : null;
        }
    }
}
