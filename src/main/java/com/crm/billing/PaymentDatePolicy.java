package com.crm.billing;

import com.crm.exception.CodedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Pul yozuvi sanasi qoidasi (buyurtmachi qarori 2026-10-05), "bugun" — Toshkent ({@link BillingClockConfig}):
 * <ul>
 *   <li>sana &gt; bugun → 400 {@code payment.date.future} (hamma rollar);</li>
 *   <li>sana &lt; bugun → faqat SUPER_ADMIN, boshqalarga 403 {@code payment.date.pastNotAllowed};</li>
 *   <li>berilmagan (null) — bugun deb olinadi, o'tadi.</li>
 * </ul>
 * To'lov (+ preview), kassa kirim/chiqim, xarajat sanasi. Imtihon to'lovi, o'tkazma, oylik to'lash va o'quvchiga
 * qaytarish sanani qabul qilmaydi — har doim bugun.
 */
@Component
@RequiredArgsConstructor
public class PaymentDatePolicy {

    private final Clock billingClock;

    public LocalDate today() {
        return LocalDate.now(billingClock);
    }

    /** @return tekshirilgan sana yoki (null bo'lsa) bugun */
    public LocalDate check(LocalDate date) {
        return check(date, today());
    }

    /** {@code today} — chaqiruvchining Toshkent "bugun"i. */
    public static LocalDate check(LocalDate date, LocalDate today) {
        if (date == null) {
            return today;
        }
        if (date.isAfter(today)) {
            throw CodedException.badRequest("payment.date.future");
        }
        if (date.isBefore(today) && !BillingAuth.hasAnyRole("SUPER_ADMIN")) {
            throw CodedException.forbidden("payment.date.pastNotAllowed");
        }
        return date;
    }
}
