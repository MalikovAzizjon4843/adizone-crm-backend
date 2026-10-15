package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code GET /api/cash-registers/{id}/handover/preview}: hali topshirilmagan yozuvlar usul va kategoriya
 * bo'yicha, kutilgan naqd va {@code previewHash} (topshirishda yuboriladi — orada yangi yozuv tushsa 409).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CashHandoverPreviewDto {
    private Long cashRegisterId;
    private String cashRegisterName;
    private BigDecimal cashBalance;
    private BigDecimal floatAmount;
    /** Topshirilishi kerak bo'lgan naqd = {@code cashBalance − floatAmount}. */
    private BigDecimal expectedCash;
    private int transactionCount;
    /** Topshirilmagan eng eski va eng yangi yozuv sanasi (o'tgan sana bilan kiritilgan yozuvlar ko'rinsin). */
    private LocalDate fromDate;
    private LocalDate toDate;
    private String previewHash;
    /** Shu kassada SUBMITTED topshirish bo'lsa — uning id si (yangisini topshirib bo'lmaydi). */
    private Long pendingHandoverId;
    private List<CashHandoverDto.Line> lines;
    private List<CashCategoryTotalDto> byCategory;
}
