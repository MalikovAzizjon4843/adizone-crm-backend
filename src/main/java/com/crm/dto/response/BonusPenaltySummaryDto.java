package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BonusPenaltySummaryDto {
    private BigDecimal totalBonus;
    private BigDecimal totalPenalty;
    private long count;
}
