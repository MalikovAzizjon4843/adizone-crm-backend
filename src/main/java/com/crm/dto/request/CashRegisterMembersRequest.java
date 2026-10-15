package com.crm.dto.request;

import lombok.Data;

import java.util.List;

/** {@code PUT /api/cash-registers/{id}/members}: kassa a'zolari (ADMIN / ACCOUNTANT) — ro'yxat to'liq almashtiriladi. */
@Data
public class CashRegisterMembersRequest {
    private List<Long> userIds;
}
