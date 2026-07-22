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
    /** Frontend: yangi lidlar */
    @com.fasterxml.jackson.annotation.JsonProperty("new")
    private long newCount;
    /** Frontend: o'quvchiga aylanganlar (status=CONVERTED) */
    private long converted;
    private long rejected;
    private Map<LeadStatus, Long> byStatus;
    private List<LeadOperatorStatsResponse> byOperator;
    private long unassigned;
}
