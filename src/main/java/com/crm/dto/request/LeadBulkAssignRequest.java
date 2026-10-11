package com.crm.dto.request;

import lombok.Data;

import java.util.List;

/**
 * {@code POST /api/leads/bulk-assign}: {@code leadIds} — 1–200 ta (takrorlari bitta sanaladi), {@code userId} — faol,
 * operator roli ({@code GET /api/leads/operators} ro'yxati sharti). Cheklovlar servisda (aniq xato kodlari bilan).
 */
@Data
public class LeadBulkAssignRequest {
    private List<Long> leadIds;
    private Long userId;
}
