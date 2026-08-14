package com.crm.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * MONTHLY davr debetining YAGONA formulasi.
 *
 * <p>Ikki joy shu sinfni chaqiradi va boshqa hech qayerda takrorlanmasin:
 * <ul>
 *   <li>{@code PaymentService.writeLedgerForPayment} — to'lov paytida PERIOD_CHARGE yozadi</li>
 *   <li>{@code BalanceExpectationService.compute} — kutilgan balansni qayta quradi</li>
 * </ul>
 * Ikki joyda ikki formula bo'lsa ular vaqt o'tib ajralib ketadi — aynan shu
 * xato balans daftarini buzgan edi.
 *
 * <pre>
 * months = floor(gross / monthlyFee)     // CHEGIRMA AYRILMAGAN gross dan
 * debit  = months x monthlyFee - discount
 * </pre>
 *
 * <p>Chegirma {@code months} ga ta'sir qilmaydi (o'quvchi baribir o'sha davrni oldi),
 * lekin debetni kamaytiradi. Natijada chegirma balansga NEYTRAL bo'ladi:
 * <pre>
 * kredit - debet = (gross - discount - balanceUsed) - (months x fee - discount)
 *                = gross - balanceUsed - months x fee     // discount qisqaradi
 * </pre>
 */
public final class PeriodChargeFormula {

    private PeriodChargeFormula() {
    }

    /**
     * Sotib olingan to'liq oylar soni. 1 ga clamp QILINMAYDI —
     * gross &lt; monthlyFee bo'lsa 0 qaytadi, ya'ni davr sotib olinmagan.
     */
    public static int months(BigDecimal gross, BigDecimal monthlyFee) {
        if (monthlyFee == null || monthlyFee.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        BigDecimal g = nz(gross);
        if (g.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        int months = g.divide(monthlyFee, 0, RoundingMode.DOWN).intValue();
        return Math.max(months, 0);
    }

    /**
     * PERIOD_CHARGE ning MUSBAT qiymati (ledgerga manfiy holda yoziladi).
     * Yozuv kerak bo'lmasa 0 qaytaradi: {@code months == 0} yoki
     * chegirma davr qiymatidan katta/teng.
     */
    public static BigDecimal debit(int months, BigDecimal discount, BigDecimal monthlyFee) {
        if (months <= 0 || monthlyFee == null || monthlyFee.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal periodCost = monthlyFee.multiply(BigDecimal.valueOf(months));
        BigDecimal debit = periodCost.subtract(nz(discount));
        return debit.compareTo(BigDecimal.ZERO) > 0 ? debit : BigDecimal.ZERO;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
