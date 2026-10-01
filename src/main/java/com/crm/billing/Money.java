package com.crm.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pul yaxlitlash — billing paketidagi YAGONA {@link RoundingMode} ishlatiladigan
 * joy (docs/design/billing-v2.md §1.3).
 *
 * <p>Qoida: formula avval to'liq aniqlikda hisoblanadi, so'ng natijaga BIR MARTA
 * {@link #uzs} qo'llanadi. Foydalanuvchi kiritgan summalar yaxlitlanmaydi —
 * kasrli bo'lsa {@link #isWhole} orqali rad etiladi (kassadagi real pul bilan
 * farq paydo bo'lmasin).
 */
public final class Money {

    /** Oraliq bo'lish aniqligi: kasr qismida 10 xona, keyin bir marta uzs(). */
    private static final int INTERMEDIATE_SCALE = 10;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private Money() {
    }

    /** UZS, butun so'm, HALF_UP. */
    public static BigDecimal uzs(BigDecimal raw) {
        return nz(raw).setScale(0, RoundingMode.HALF_UP);
    }

    /** {@code a / b} oraliq aniqlik bilan (yaxlitlash keyin, {@link #uzs} da). */
    public static BigDecimal divide(BigDecimal a, BigDecimal b) {
        return nz(a).divide(b, INTERMEDIATE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Chegirmali narx: {@code uzs(price × (100 − d) / 100)}.
     * {@code d} null bo'lsa 0. Davr (MONTHLY) va dars (PER_LESSON) narxi shu bilan.
     */
    public static BigDecimal discounted(BigDecimal price, BigDecimal discountPercentage) {
        BigDecimal d = nz(discountPercentage);
        BigDecimal raw = divide(nz(price).multiply(HUNDRED.subtract(d)), HUNDRED);
        return uzs(raw);
    }

    /** {@code uzs(amount × part / whole)} — muzlatish qaytarimi kabi proporsiyalar uchun. */
    public static BigDecimal proportion(BigDecimal amount, long part, long whole) {
        if (whole <= 0) {
            throw new IllegalArgumentException("whole > 0 bo'lishi kerak");
        }
        BigDecimal raw = divide(nz(amount).multiply(BigDecimal.valueOf(part)), BigDecimal.valueOf(whole));
        return uzs(raw);
    }

    /** Kasr qismi yo'qmi (630000.00 — ha, 630000.50 — yo'q). */
    public static boolean isWhole(BigDecimal value) {
        if (value == null) {
            return true;
        }
        return value.signum() == 0 || value.stripTrailingZeros().scale() <= 0;
    }

    /** {@code floor(a / b)} butun son sifatida (b > 0). */
    public static long floorDiv(BigDecimal a, BigDecimal b) {
        return nz(a).divide(b, 0, RoundingMode.FLOOR).longValueExact();
    }

    /** {@code ceil(a / b)} butun son sifatida (b > 0) — masalan "necha oy qarz". */
    public static long ceilDiv(BigDecimal a, BigDecimal b) {
        return nz(a).divide(b, 0, RoundingMode.CEILING).longValueExact();
    }

    /** Kesh/JSON uchun bir xil ko'rinish: scale 0 (butun so'm). */
    public static BigDecimal normalize(BigDecimal value) {
        return uzs(value);
    }

    public static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    /** Ikki summa teng (scale farqi hisobga olinmaydi). */
    public static boolean eq(BigDecimal a, BigDecimal b) {
        return nz(a).compareTo(nz(b)) == 0;
    }
}
