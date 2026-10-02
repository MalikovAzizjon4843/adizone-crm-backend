package com.crm.dto.request;

import lombok.Data;

/** {@code POST /api/contracts/{id}/cancel} — sabab majburiy (3–500 belgi). */
@Data
public class ContractCancelRequest {
    private String reason;
}
