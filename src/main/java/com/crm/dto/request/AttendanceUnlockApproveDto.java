package com.crm.dto.request;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class AttendanceUnlockApproveDto {
    private BigDecimal penaltyAmount;
    private String penaltyReason;
}
