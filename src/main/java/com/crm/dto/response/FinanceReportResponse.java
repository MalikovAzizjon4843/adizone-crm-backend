package com.crm.dto.response;
import lombok.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
/**
 * {@code GET /api/finance/report} — faqat SUPER_ADMIN (V86).
 * {@code netProfit = totalIncome + examFees + otherIncome + bookSales − totalExpenses − payrollPaid − refunds − bookPurchases}
 * (docs/design/cash-and-books.md §1.3). Oylikning kassa chiqimi {@code totalExpenses} ga kirmaydi —
 * {@code payrollPaid} dan, ikki marta ayirilmaydi.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FinanceReportResponse {
    private BigDecimal totalIncome;
    /** Imtihon to'lovlari (kassa: kirim − REVERSAL), leaves-exams-contracts §4.2. */
    private BigDecimal examFees;
    /** Kassadagi boshqa kirim kategoriyalari (OTHER_INCOME, CASH_SURPLUS ...) − bekor qilinganlari (V86). */
    private BigDecimal otherIncome;
    /**
     * Xarajatlar (V86): kassadan chiqimlar kategoriya bo'yicha (oylik va qaytarishdan tashqari; kamomad ham)
     * + kassasiz xarajatlar ({@code expenses.cash_register_id IS NULL}).
     */
    private BigDecimal totalExpenses;
    /** O'quvchiga qaytarilgan pul (REFUND) − bekor qilinganlari (V86). */
    private BigDecimal refunds;
    /** Topshirishdagi kamomad (CASH_SHORTAGE) — {@code totalExpenses} ichida, ma'lumot uchun alohida. */
    private BigDecimal cashShortage;
    /** Xarajatlar guruh bo'yicha: DAILY / STRATEGIC / SYSTEM (kamomad). */
    private Map<String, BigDecimal> expenseByScope;
    /** Kitob sotuvi tushumi (BOOK_SALE, V87) — {@code otherIncome} ga kirmaydi. */
    private BigDecimal bookSales;
    /** Kitob sotuvi tur bo'yicha: STUDY (o'quv), FICTION (badiiy). */
    private Map<String, BigDecimal> bookSalesByKind;
    /** Yetkazib beruvchilarga to'langan (BOOK_PURCHASE, V87) — {@code totalExpenses} ga kirmaydi. */
    private BigDecimal bookPurchases;
    /** Σ PAID oylik {@code netSalary}, {@code paidAt} davr ichida; CANCELLED kirmaydi. */
    private BigDecimal payrollPaid;
    /** Rol bo'yicha: TEACHER / ADMIN / SALES_MANAGER (xodimi aniqlanmagan eski yozuv — OTHER). */
    private Map<String, BigDecimal> payrollByRole;
    private BigDecimal netProfit;
    private Map<String, BigDecimal> incomeByCategory;
    private Map<String, BigDecimal> expenseByCategory;
    /**
     * Kassaga tushgan kirim to'lov usuli guruhlari bo'yicha (CASH, CARD, TERMINAL, ONLINE,
     * OTHER; eski BANK → TERMINAL): {@code cash_transactions} INCOME − bekor qilinganlari, sana — {@code transaction_date}.
     * Barcha kassa kirimi (imtihon to'lovi, qo'lda kirim ham) — {@code totalIncome} (faqat
     * o'quvchi to'lovlari) bilan teng bo'lishi shart emas. CASH_AND_CARD qismlari CASH/CARD ga.
     */
    private Map<String, BigDecimal> incomeByMethod;
    /** Kassadan chiqim guruhlar bo'yicha: EXPENSE (xarajat, oylik) − bekor qilinganlari. */
    private Map<String, BigDecimal> expenseByMethod;
    /** To'liq oqim (kirim, chiqim, o'tkazmalar) guruhlar bo'yicha — barcha kassalar birga. */
    private List<CashChannelSummaryDto> cashFlowByMethod;
    private String period;
}
