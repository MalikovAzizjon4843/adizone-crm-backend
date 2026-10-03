package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Kassa oqimi bitta to'lov usuli guruhi ({@link com.crm.entity.enums.PaymentChannel}) bo'yicha.
 * Hamma summa musbat; {@code net} — ishorali natija. Davr berilmasa (balans) {@code net} = shu
 * guruhdagi qoldiq.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CashChannelSummaryDto {
    /** CASH | CARD | TERMINAL | ONLINE | BANK | OTHER; jami qatorda null. */
    private String channel;
    private String label;
    /** Shu guruhga kiruvchi {@code PaymentMethod} lar (CASH_AND_CARD — CASH va CARD da). */
    private List<String> methods;
    private BigDecimal income;
    /** Bekor qilingan kirimlar (REVERSAL, OUT). */
    private BigDecimal incomeReversed;
    private BigDecimal expense;
    /** Bekor qilingan chiqimlar (REVERSAL, IN) — pul kassaga qaytgan. */
    private BigDecimal expenseReversed;
    private BigDecimal transferIn;
    private BigDecimal transferOut;
    /** income − incomeReversed */
    private BigDecimal netIncome;
    /** expense − expenseReversed */
    private BigDecimal netExpense;
    /** netIncome − netExpense + transferIn − transferOut */
    private BigDecimal net;
}
