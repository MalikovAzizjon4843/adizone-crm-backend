package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** {@code GET /api/cash-registers/{id}/by-method}: davr oqimi to'lov usuli guruhlari bo'yicha. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CashChannelReportDto {
    private Long cashRegisterId;
    /** null — boshidan. */
    private LocalDate from;
    /** null — bugungacha. */
    private LocalDate to;
    /** Har doim 5 qator (CASH, CARD, TERMINAL, ONLINE, OTHER), nol bo'lsa ham. */
    @Builder.Default
    private List<CashChannelSummaryDto> channels = new ArrayList<>();
    private CashChannelSummaryDto total;
}
