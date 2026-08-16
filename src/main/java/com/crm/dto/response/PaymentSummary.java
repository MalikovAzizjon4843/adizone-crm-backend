package com.crm.dto.response;

import lombok.*;

import java.math.BigDecimal;

/**
 * GET /api/payments aggregati.
 *
 * <p>MUHIM: qiymatlar joriy sahifadan emas, FILTRGA MOS BARCHA qatorlardan
 * hisoblanadi. Frontend sahifadagi qatorlarni qo'shib chiqmasin — size dan
 * ko'p to'lov bo'lsa u raqam noto'g'ri bo'ladi.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentSummary {

    /** SUM(amount) — gross, chegirma ayrilmagan. */
    private BigDecimal totalAmount;

    /**
     * SUM(COALESCE(cash_amount, amount)) — kassaga tushgan real pul.
     * Eski satrlarda cash_amount NULL: o'shanda amount aynan naqd summani bildirgan.
     */
    private BigDecimal totalCashAmount;

    /** Filtrga mos jami qatorlar soni (Page.totalElements bilan bir xil). */
    private long totalCount;
}
