package com.crm.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Xronologik FIFO — har majburiyat <b>qachon</b> to'liq yopilgani (director-dashboard §3.2.2).
 * {@link FifoDebt} "hozir qaysi majburiyat ochiq"ni beradi; bu klass shu taqsimotni kreditlar
 * kelish tartibida o'ynab, yopgan kreditni topadi. Qaysi majburiyatlar yopilgani FifoDebt bilan
 * bir xil (pul eng eski majburiyatga ketadi).
 *
 * <ol>
 *   <li>REVERSAL / qaytarim zanjiri asl yozuvga qo'shib netlanadi ({@code relatedTxId}).</li>
 *   <li>Neytral yozuvlar ({@link Line#neutral}) — v1 PERIOD_CHARGE, {@code [ledger-repair]},
 *       MIGRATION — va ularga bog'langan zanjirlar chiqariladi (yig'indisi 0, billing-v2 §9.2).</li>
 *   <li>Majburiyatlar (manfiy net) {@code (effective_date, id)} tartibida; kreditlar ham shu
 *       tartibda keladi va ketma-ket yopadi. Yopilgan payt = yopgan kreditning sanasi.</li>
 * </ol>
 */
public final class PeriodCoverage {

    private PeriodCoverage() {
    }

    public record Line(Long id, BigDecimal amount, LocalDate effectiveDate, Long relatedTxId,
                       LocalDateTime createdAt, boolean neutral) {
    }

    /** Majburiyat (asl yozuv id si) qaysi kredit bilan, qachon to'liq yopildi. */
    public record Covered(LocalDate paidOn, LocalDateTime paidAt, Long paidTxId) {
    }

    private record Root(Line line, BigDecimal net) {
    }

    public static Map<Long, Covered> compute(List<Line> lines) {
        Map<Long, Line> byId = new HashMap<>();
        for (Line l : lines) {
            if (l.id() != null) {
                byId.put(l.id(), l);
            }
        }
        Map<Line, BigDecimal> net = new LinkedHashMap<>();
        for (Line l : lines) {
            Line root = rootOf(l, byId);
            if (root.neutral()) {
                continue;
            }
            net.merge(root, Money.nz(l.amount()), BigDecimal::add);
        }

        Comparator<Root> order = Comparator
            .comparing((Root r) -> r.line().effectiveDate(), Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(r -> r.line().id(), Comparator.nullsFirst(Comparator.naturalOrder()));
        List<Root> obligations = new ArrayList<>();
        List<Root> credits = new ArrayList<>();
        net.forEach((line, amount) -> {
            if (amount.signum() < 0) {
                obligations.add(new Root(line, amount.negate()));
            } else if (amount.signum() > 0) {
                credits.add(new Root(line, amount));
            }
        });
        obligations.sort(order);
        credits.sort(order);

        Map<Long, Covered> out = new HashMap<>();
        int o = 0;
        BigDecimal remaining = obligations.isEmpty() ? BigDecimal.ZERO : obligations.get(0).net();
        for (Root credit : credits) {
            BigDecimal funds = credit.net();
            while (funds.signum() > 0 && o < obligations.size()) {
                BigDecimal take = funds.min(remaining);
                funds = funds.subtract(take);
                remaining = remaining.subtract(take);
                if (remaining.signum() == 0) {
                    Line ob = obligations.get(o).line();
                    Line cr = credit.line();
                    out.put(ob.id(), new Covered(cr.effectiveDate(), cr.createdAt(), cr.id()));
                    o++;
                    remaining = o < obligations.size() ? obligations.get(o).net() : BigDecimal.ZERO;
                }
            }
        }
        return out;
    }

    private static Line rootOf(Line line, Map<Long, Line> byId) {
        Line current = line;
        int guard = 0;
        while (current.relatedTxId() != null && byId.containsKey(current.relatedTxId()) && guard++ < 16) {
            current = byId.get(current.relatedTxId());
        }
        return current;
    }
}
