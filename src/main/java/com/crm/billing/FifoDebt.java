package com.crm.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FIFO — eng eski to'lanmagan majburiyat sanasi ({@code debtSince}),
 * docs/design/billing-v2.md §4.1. Sof funksiya.
 *
 * <pre>
 * groupKey(tx) = tx.relatedTxId ?? tx.id   (asl yozuv shu ro'yxatda bo'lsa; aks holda o'zi)
 * net(g)       = Σ amount (guruh ichida)
 * obligations  = { g : net(g) < 0 }, tartib: (asl.effective_date, asl.id) o'sishi
 * funds        = Σ net(g) > 0
 * for g in obligations:
 *     if funds ≥ |net(g)|: funds −= |net(g)|
 *     else: debtSince = asl(g).effective_date; break
 * </pre>
 * Nega guruhlash: to'lov bekor qilinsa (REVERSAL) qarz bekor qilingan kundan emas,
 * ASL charge sanasidan qaytadi; muzlatish qaytarimi (PERIOD_REFUND) aynan o'sha davr
 * majburiyatini kamaytiradi. TRANSFER juftining aslı boshqa SG da — ro'yxatda
 * yo'q, shuning uchun o'z guruhi bo'ladi.
 */
public final class FifoDebt {

    private FifoDebt() {
    }

    /** FIFO uchun kerakli minimal yozuv. */
    public record Line(Long id, BigDecimal amount, LocalDate effectiveDate, Long relatedTxId) {
    }

    /** {@code debtSince} null ⇔ balans ≥ 0. */
    public record Result(BigDecimal balance, LocalDate debtSince) {
        public BigDecimal debt() {
            return balance.signum() < 0 ? balance.negate() : BigDecimal.ZERO;
        }
    }

    public static Result compute(List<Line> lines) {
        Map<Long, Line> byId = new HashMap<>();
        for (Line l : lines) {
            if (l.id() != null) {
                byId.put(l.id(), l);
            }
        }

        // asl yozuv → guruh summasi (kiritilish tartibi saqlanadi)
        Map<Line, BigDecimal> net = new LinkedHashMap<>();
        BigDecimal balance = BigDecimal.ZERO;
        for (Line l : lines) {
            Line origin = rootOf(l, byId);
            net.merge(origin, Money.nz(l.amount()), BigDecimal::add);
            balance = balance.add(Money.nz(l.amount()));
        }

        BigDecimal funds = BigDecimal.ZERO;
        List<Map.Entry<Line, BigDecimal>> obligations = new ArrayList<>();
        for (Map.Entry<Line, BigDecimal> e : net.entrySet()) {
            if (e.getValue().signum() > 0) {
                funds = funds.add(e.getValue());
            } else if (e.getValue().signum() < 0) {
                obligations.add(e);
            }
        }
        obligations.sort(Comparator
            .comparing((Map.Entry<Line, BigDecimal> e) -> e.getKey().effectiveDate(),
                Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(e -> e.getKey().id(), Comparator.nullsFirst(Comparator.naturalOrder())));

        for (Map.Entry<Line, BigDecimal> e : obligations) {
            BigDecimal owed = e.getValue().negate();
            if (funds.compareTo(owed) >= 0) {
                funds = funds.subtract(owed);
            } else {
                return new Result(balance, e.getKey().effectiveDate());
            }
        }
        return new Result(balance, null);
    }

    /** Teskari / qaytarim zanjirining boshi (shu SG ro'yxatidagi asl yozuv). */
    private static Line rootOf(Line line, Map<Long, Line> byId) {
        Line current = line;
        int guard = 0;
        while (current.relatedTxId() != null && byId.containsKey(current.relatedTxId()) && guard++ < 16) {
            current = byId.get(current.relatedTxId());
        }
        return current;
    }
}
