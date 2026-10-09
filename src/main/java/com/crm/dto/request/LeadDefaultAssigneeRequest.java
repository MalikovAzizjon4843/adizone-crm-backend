package com.crm.dto.request;

import lombok.Data;

/** {@code PUT /api/settings/leads/default-assignee}: {@code userId = null} — sozlamani tozalash. */
@Data
public class LeadDefaultAssigneeRequest {
    private Long userId;
}
