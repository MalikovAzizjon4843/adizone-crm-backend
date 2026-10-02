package com.crm.dto.request;

import lombok.Data;

/**
 * {@code approve}: {@code paid} majburiy (D2) — haqli yoki haqsiz; {@code reject}/{@code cancel}: {@code note}
 * (rad etishda majburiy, 3–500 belgi).
 */
@Data
public class LeaveDecisionRequest {
    private Boolean paid;
    private String note;
}
