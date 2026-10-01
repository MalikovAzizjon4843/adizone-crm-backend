package com.crm.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Billing qatori — preview/create/cancel/freeze javoblarida (docs/design/billing-v2.md §5.4).
 * {@code type} — ledger turi (PERIOD_CHARGE, PAYMENT, DISCOUNT, BONUS, PENALTY, REVERSAL, ...).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BillingLineDto {
    private String type;
    /** Belgili summa: + kredit, − majburiyat. */
    private BigDecimal amount;
    private LocalDate effectiveDate;
    private String note;
    private Long bonusPenaltyId;
    /** true — accrual hali yozilmagan, to'lov bilan birga yoziladi. */
    private Boolean pending;
    /** Yozilgandan keyin (create/cancel) — ledger yozuvi id si. */
    private Long ledgerTxId;
}
