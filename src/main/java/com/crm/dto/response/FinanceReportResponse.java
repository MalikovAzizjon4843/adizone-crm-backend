package com.crm.dto.response;
import lombok.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
/**
 * {@code GET /api/finance/report}. {@code netProfit = totalIncome + examFees − totalExpenses − payrollPaid}
 * (payroll-v2 §11 #8). Oylik kassadan chiqim ({@code CashTransaction}) sifatida ham yoziladi, lekin
 * {@code totalExpenses} faqat {@code Expense} jadvalidan — oylik ikki marta ayirilmaydi.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FinanceReportResponse {
    private BigDecimal totalIncome;
    /** Imtihon to'lovlari (kassa: kirim − REVERSAL), leaves-exams-contracts §4.2. */
    private BigDecimal examFees;
    private BigDecimal totalExpenses;
    /** Σ PAID oylik {@code netSalary}, {@code paidAt} davr ichida; CANCELLED kirmaydi. */
    private BigDecimal payrollPaid;
    /** Rol bo'yicha: TEACHER / ADMIN / SALES_MANAGER (xodimi aniqlanmagan eski yozuv — OTHER). */
    private Map<String, BigDecimal> payrollByRole;
    private BigDecimal netProfit;
    private Map<String, BigDecimal> incomeByCategory;
    private Map<String, BigDecimal> expenseByCategory;
    /**
     * Kassaga tushgan kirim to'lov usuli guruhlari bo'yicha (CASH, CARD, TERMINAL, ONLINE, BANK,
     * OTHER): {@code cash_transactions} INCOME − bekor qilinganlari, sana — {@code transaction_date}.
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
