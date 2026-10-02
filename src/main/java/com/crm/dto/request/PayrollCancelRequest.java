package com.crm.dto.request;

import lombok.Data;

/** {@code POST /api/payroll/{id}/cancel} — faqat SUPER_ADMIN, sabab majburiy (3–500). */
@Data
public class PayrollCancelRequest {
    private String reason;
}
