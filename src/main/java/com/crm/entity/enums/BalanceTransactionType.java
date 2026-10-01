package com.crm.entity.enums;

/**
 * Ledger ({@code balance_transactions}) yozuv turlari — docs/design/billing-v2.md §2.
 *
 * <p>Mavjud qiymatlar o'chirilmaydi: eski yozuvlar o'qilishi kerak. Belgi qoidasi
 * {@link #sign()} da, uni {@code LedgerService} tekshiradi.
 */
public enum BalanceTransactionType {
    /** PER_LESSON: davomat billable bo'ldi (−). */
    LESSON_CHARGE(Sign.NEGATIVE),
    /** PER_LESSON: billable → EXCUSED, aynan o'sha charge summasi qaytadi (+). */
    LESSON_REFUND(Sign.POSITIVE),
    /** To'lov: kassaga tushgan real pul (+). */
    PAYMENT(Sign.POSITIVE),
    /** MONTHLY: davr boshlanganda accrual (−). */
    PERIOD_CHARGE(Sign.NEGATIVE),
    /** MONTHLY: muzlatishda davrning ishlatilmagan qismi (+). */
    PERIOD_REFUND(Sign.POSITIVE),
    /** Legacy — endi yozilmaydi, faqat o'qiladi. */
    FREEZE(Sign.LEGACY),
    /** Legacy — endi yozilmaydi, faqat o'qiladi. */
    UNFREEZE(Sign.LEGACY),
    /** SUPER_ADMIN qo'lda tuzatish (±). */
    MANUAL_ADJUST(Sign.ANY),
    /** To'lovdagi bir martalik chegirma (+). */
    DISCOUNT(Sign.POSITIVE),
    /** O'quvchi bonusi qo'llandi (+). */
    BONUS(Sign.POSITIVE),
    /** O'quvchi jarimasi qo'llandi (−). */
    PENALTY(Sign.NEGATIVE),
    /** Istalgan kredit/debetni bekor qilish — aslining teskarisi (∓). */
    REVERSAL(Sign.OPPOSITE_OF_RELATED),
    /** transfer-group / balance-transfer: eski yozilmadan chiqim (±). */
    TRANSFER_OUT(Sign.ANY),
    /** transfer-group / balance-transfer: yangi yozilmaga kirim (±). */
    TRANSFER_IN(Sign.ANY),
    /** Migratsiya: eski PERIOD_CHARGE / ta'mir yozuvlarini neytrallash (±). */
    MIGRATION(Sign.ANY),
    /** O'quvchiga musbat balansdan naqd qaytarish (−), kassa chiqimi bilan. */
    REFUND_PAYOUT(Sign.NEGATIVE);

    public enum Sign { POSITIVE, NEGATIVE, ANY, OPPOSITE_OF_RELATED, LEGACY }

    private final Sign sign;

    BalanceTransactionType(Sign sign) {
        this.sign = sign;
    }

    public Sign sign() {
        return sign;
    }
}
