package com.crm.service;

import com.crm.dto.response.CashChannelSummaryDto;
import com.crm.entity.enums.PaymentChannel;
import com.crm.entity.enums.PaymentMethod;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Kassa oqimi to'lov usuli guruhlari ({@link PaymentChannel}) bo'yicha — {@code cash_transactions}
 * dan bitta agregat so'rov bilan (yozuvlar yuklanmaydi).
 *
 * <p>Yo'nalish {@code CashRegisterService.direction} bilan bir xil: INCOME → IN, EXPENSE → OUT,
 * TRANSFER kirim qatori ({@code related_tx_id} bor yoki eski nomi "(kirim)") → IN, qolgani OUT;
 * REVERSAL — asl yozuvga teskari, asl topilmasa to'lov/imtihon teskarisi OUT, boshqasi IN.
 * Taqsimot {@link PaymentChannel#split} bilan bir xil: CASH_AND_CARD qismlari CASH/CARD ga,
 * qismsiz eski yozuv to'liq CASH ga — saqlangan {@code cash_balance/plastic_balance} ham shunday.
 *
 * <p>Ixtiyoriy filtrlar SQL ga faqat berilganda qo'shiladi ({@code :p IS NULL OR …} yo'q —
 * PostgreSQL parametr tipini aniqlay olmaydi).
 */
@Service
public class CashChannelService {

    @PersistenceContext
    private EntityManager em;

    /** Bitta kassa uchun kanallar oqimi; {@code registerId = null} — hamma kassalar birga. */
    @Transactional(readOnly = true)
    public Map<PaymentChannel, Flow> flows(Long registerId, LocalDate from, LocalDate to) {
        Map<Long, Map<PaymentChannel, Flow>> byRegister = aggregate(registerId, from, to);
        Map<PaymentChannel, Flow> merged = emptyFlows();
        for (Map<PaymentChannel, Flow> one : byRegister.values()) {
            one.forEach((c, f) -> merged.get(c).add(f));
        }
        return merged;
    }

    /** Hamma kassalar, har biri alohida (kassalar ro'yxati uchun bitta so'rov). */
    @Transactional(readOnly = true)
    public Map<Long, Map<PaymentChannel, Flow>> flowsByRegister() {
        return aggregate(null, null, null);
    }

    public static List<CashChannelSummaryDto> toDtos(Map<PaymentChannel, Flow> flows) {
        List<CashChannelSummaryDto> out = new ArrayList<>();
        for (PaymentChannel c : PaymentChannel.values()) {
            out.add(flows.getOrDefault(c, new Flow()).toDto(c));
        }
        return out;
    }

    public static CashChannelSummaryDto total(Map<PaymentChannel, Flow> flows) {
        Flow sum = new Flow();
        flows.values().forEach(sum::add);
        return sum.toDto(null);
    }

    public static Map<PaymentChannel, Flow> emptyFlows() {
        Map<PaymentChannel, Flow> out = new EnumMap<>(PaymentChannel.class);
        for (PaymentChannel c : PaymentChannel.values()) {
            out.put(c, new Flow());
        }
        return out;
    }

    private Map<Long, Map<PaymentChannel, Flow>> aggregate(Long registerId, LocalDate from, LocalDate to) {
        StringBuilder where = new StringBuilder("WHERE 1 = 1");
        Map<String, Object> params = new HashMap<>();
        if (registerId != null) {
            where.append(" AND t.cash_register_id = :reg");
            params.put("reg", registerId);
        }
        if (from != null) {
            where.append(" AND t.transaction_date >= :f");
            params.put("f", from);
        }
        if (to != null) {
            where.append(" AND t.transaction_date <= :t");
            params.put("t", to);
        }
        String sql = """
            SELECT x.reg, x.method, x.kind, x.dir, SUM(x.amount), SUM(x.cash_amt), SUM(x.card_amt)
            FROM (
                SELECT t.cash_register_id AS reg, t.payment_method AS method, t.type AS kind,
                       CASE WHEN t.type = 'INCOME' THEN 1
                            WHEN t.type = 'EXPENSE' THEN -1
                            WHEN t.type = 'TRANSFER' THEN
                                CASE WHEN t.related_tx_id IS NOT NULL
                                       OR t.transaction_name LIKE '%(kirim)%' THEN 1 ELSE -1 END
                            WHEN o.type = 'INCOME' THEN -1
                            WHEN o.type = 'EXPENSE' THEN 1
                            WHEN t.payment_id IS NOT NULL OR t.exam_registration_id IS NOT NULL THEN -1
                            ELSE 1 END AS dir,
                       t.amount AS amount,
                       CASE WHEN t.cash_part IS NOT NULL AND t.card_part IS NOT NULL
                            THEN t.cash_part ELSE t.amount END AS cash_amt,
                       CASE WHEN t.cash_part IS NOT NULL AND t.card_part IS NOT NULL
                            THEN t.card_part ELSE 0 END AS card_amt
                FROM cash_transactions t
                LEFT JOIN cash_transactions o ON o.id = t.related_tx_id AND t.type = 'REVERSAL'
                """ + where + """
            ) x
            GROUP BY x.reg, x.method, x.kind, x.dir
            """;
        Query q = em.createNativeQuery(sql);
        params.forEach(q::setParameter);

        Map<Long, Map<PaymentChannel, Flow>> out = new HashMap<>();
        for (Object row : q.getResultList()) {
            Object[] r = (Object[]) row;
            Long reg = ((Number) r[0]).longValue();
            PaymentMethod method = r[1] != null ? PaymentMethod.parseOrNull(r[1].toString()) : null;
            String kind = r[2] != null ? r[2].toString() : "";
            int dir = ((Number) r[3]).intValue();
            BigDecimal amount = dec(r[4]);
            Map<PaymentChannel, BigDecimal> parts = method == PaymentMethod.CASH_AND_CARD
                ? PaymentChannel.split(method, amount, dec(r[5]), dec(r[6]))
                : PaymentChannel.split(method, amount, null, null);
            Map<PaymentChannel, Flow> flows = out.computeIfAbsent(reg, k -> emptyFlows());
            parts.forEach((c, v) -> flows.get(c).record(kind, dir, v));
        }
        return out;
    }

    private static BigDecimal dec(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        return v instanceof BigDecimal b ? b : new BigDecimal(v.toString());
    }

    /** Bitta kanal oqimi; hamma maydon musbat. */
    public static final class Flow {
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal incomeReversed = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        BigDecimal expenseReversed = BigDecimal.ZERO;
        BigDecimal transferIn = BigDecimal.ZERO;
        BigDecimal transferOut = BigDecimal.ZERO;

        void record(String kind, int dir, BigDecimal v) {
            switch (kind) {
                case "INCOME" -> income = income.add(v);
                case "EXPENSE" -> expense = expense.add(v);
                case "TRANSFER" -> {
                    if (dir > 0) {
                        transferIn = transferIn.add(v);
                    } else {
                        transferOut = transferOut.add(v);
                    }
                }
                default -> {
                    // REVERSAL: OUT — kirim bekor qilindi, IN — chiqim bekor qilindi
                    if (dir < 0) {
                        incomeReversed = incomeReversed.add(v);
                    } else {
                        expenseReversed = expenseReversed.add(v);
                    }
                }
            }
        }

        void add(Flow o) {
            income = income.add(o.income);
            incomeReversed = incomeReversed.add(o.incomeReversed);
            expense = expense.add(o.expense);
            expenseReversed = expenseReversed.add(o.expenseReversed);
            transferIn = transferIn.add(o.transferIn);
            transferOut = transferOut.add(o.transferOut);
        }

        public BigDecimal netIncome() {
            return income.subtract(incomeReversed);
        }

        public BigDecimal netExpense() {
            return expense.subtract(expenseReversed);
        }

        public BigDecimal net() {
            return netIncome().subtract(netExpense()).add(transferIn).subtract(transferOut);
        }

        CashChannelSummaryDto toDto(PaymentChannel c) {
            return CashChannelSummaryDto.builder()
                .channel(c != null ? c.name() : null)
                .label(c != null ? c.getLabel() : "Jami")
                .methods(c != null ? c.methods().stream().map(Enum::name).toList() : null)
                .income(income)
                .incomeReversed(incomeReversed)
                .expense(expense)
                .expenseReversed(expenseReversed)
                .transferIn(transferIn)
                .transferOut(transferOut)
                .netIncome(netIncome())
                .netExpense(netExpense())
                .net(net())
                .build();
        }
    }
}
