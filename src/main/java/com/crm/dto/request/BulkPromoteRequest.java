package com.crm.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class BulkPromoteRequest {
    @NotNull(message = "Source group ID is required")
    private Long sourceGroupId;

    @NotNull(message = "Target group ID is required")
    private Long targetGroupId;

    @Min(value = 1, message = "Month must be between 1 and 12")
    @Max(value = 12, message = "Month must be between 1 and 12")
    private int sourceMonth;

    private int sourceYear;

    @Min(value = 1, message = "Month must be between 1 and 12")
    @Max(value = 12, message = "Month must be between 1 and 12")
    private int targetMonth;

    private int targetYear;

    private String remarks;
}
