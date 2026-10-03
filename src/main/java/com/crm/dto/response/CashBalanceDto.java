package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CashBalanceDto {
    private Long cashRegisterId;
    private BigDecimal balance;
    private BigDecimal cashBalance;
    private BigDecimal plasticBalance;
    /**
     * Qoldiq to'lov usuli guruhlari bo'yicha (CASH, CARD, TERMINAL, ONLINE, BANK, OTHER) —
     * kassa tranzaksiyalaridan; {@code net} — shu guruhdagi qoldiq.
     */
    private List<CashChannelSummaryDto> byMethod;
    /**
     * Saqlangan {@code cash_balance} − tranzaksiyalardan chiqqan CASH. Nol emas bo'lsa — kassa
     * balansi tranzaksiyasiz o'zgargan (boshlang'ich qoldiq, qo'lda tuzatish).
     */
    private BigDecimal unattributedCash;
    /** Saqlangan {@code plastic_balance} − (CARD + TERMINAL + ONLINE + BANK + OTHER). */
    private BigDecimal unattributedNonCash;
    /** Ikkala farq ham nol. */
    private boolean reconciled;
}
