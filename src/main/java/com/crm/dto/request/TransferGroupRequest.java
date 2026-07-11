package com.crm.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TransferGroupRequest {
    /** Optional — if set, closes this active enrollment. */
    private Long fromGroupId;

    @NotNull(message = "toGroupId majburiy")
    private Long toGroupId;

    private String reason;
    private String note;
}
