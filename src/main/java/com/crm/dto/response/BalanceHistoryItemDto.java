package com.crm.dto.response;

import com.crm.entity.enums.BalanceTransactionType;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BalanceHistoryItemDto {
    private LocalDateTime date;
    private BalanceTransactionType type;
    private String typeLabel;
    private BigDecimal amount;
    private BigDecimal balanceAfter;
    private String note;
    private Long groupId;
    private String groupName;
    private String createdBy;

    // ── Billing v2 (§10.2) ──
    private Long id;
    /** FIFO uchun sana (davr boshi, to'lov sanasi, muzlatish sanasi...). */
    private LocalDate effectiveDate;
    /** REVERSAL / qaytarim / ko'chirish jufti. */
    private Long relatedTxId;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private PeriodRef billingPeriod;
    /** PAYMENT / DISCOUNT uchun. */
    private Long paymentId;
    private String receiptNumber;

    public record PeriodRef(LocalDate start, LocalDate end) {
    }
}
