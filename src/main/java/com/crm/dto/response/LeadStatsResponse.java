package com.crm.dto.response;

import com.crm.entity.enums.LeadStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeadStatsResponse {
    private long total;
    private Map<LeadStatus, Long> byStatus;
    private List<LeadOperatorStatsResponse> byOperator;
    private long unassigned;
}
