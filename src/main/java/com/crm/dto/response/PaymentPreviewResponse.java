package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** To'lov hisobining oldindan ko'rinishi — hech narsa saqlanmaydi. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentPreviewResponse {
    /** So'rovdagi to'liq summa. */
    private BigDecimal gross;
    private BigDecimal discount;
    /** gross - discount */
    private BigDecimal payable;
    /** Balansdan qoplanadigan qism. */
    private BigDecimal balanceUsed;
    /** Kassaga tushadigan real pul: payable - balanceUsed. */
    private BigDecimal cashAmount;
    /** O'quvchining hozirgi balansi. */
    private BigDecimal studentBalance;
    /** studentBalance - balanceUsed */
    private BigDecimal balanceAfter;
}
