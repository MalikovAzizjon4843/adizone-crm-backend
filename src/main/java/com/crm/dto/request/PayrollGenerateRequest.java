package com.crm.dto.request;

import lombok.Data;

/** {@code POST /api/payroll/generate} body (query parametrlari ham qabul qilinadi). */
@Data
public class PayrollGenerateRequest {
    private Integer month;
    private Integer year;
    /** true — mavjud DRAFT lar ham qayta hisoblanadi (APPROVED/PAID ga tegilmaydi). */
    private Boolean recalculate;
}
