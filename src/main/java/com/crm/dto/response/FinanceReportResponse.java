package com.crm.dto.response;
import lombok.*;
import java.math.BigDecimal;
import java.util.Map;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FinanceReportResponse {
    private BigDecimal totalIncome;
    private BigDecimal totalExpenses;
    private BigDecimal netProfit;
    private Map<String, BigDecimal> incomeByCategory;
    private Map<String, BigDecimal> expenseByCategory;
    private String period;
}
