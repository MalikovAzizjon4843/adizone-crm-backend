package com.crm.dto.response;

import com.crm.entity.enums.BalanceTransactionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
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
}
