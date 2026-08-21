package com.crm.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class BulkPromoteRequest {
    @NotNull(message = "{bulkPromote.sourceGroupId.required}")
    private Long sourceGroupId;

    @NotNull(message = "{bulkPromote.targetGroupId.required}")
    private Long targetGroupId;

    @Min(value = 1, message = "{bulkPromote.sourceMonth.min}")
    @Max(value = 12, message = "{bulkPromote.sourceMonth.max}")
    private int sourceMonth;

    private int sourceYear;

    @Min(value = 1, message = "{bulkPromote.targetMonth.min}")
    @Max(value = 12, message = "{bulkPromote.targetMonth.max}")
    private int targetMonth;

    private int targetYear;

    private String remarks;
}
