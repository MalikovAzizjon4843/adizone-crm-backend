package com.crm.dto.response;
import lombok.*;
import java.math.BigDecimal;
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
    private String period;
}
