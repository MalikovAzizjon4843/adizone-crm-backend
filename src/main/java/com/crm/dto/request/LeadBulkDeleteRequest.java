package com.crm.dto.request;

import lombok.Data;

import java.util.List;

/** {@code POST /api/leads/bulk-delete}: {@code leadIds} — 1–200 ta (takrorlari bitta sanaladi). */
@Data
public class LeadBulkDeleteRequest {
    private List<Long> leadIds;
}
