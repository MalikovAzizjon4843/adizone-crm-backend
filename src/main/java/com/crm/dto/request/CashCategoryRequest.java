package com.crm.dto.request;

import lombok.Data;

/** {@code POST/PUT /api/cash-categories} (faqat SA). PUT da faqat name, sortOrder, active o'zgaradi. */
@Data
public class CashCategoryRequest {
    private String code;
    /** IN | OUT */
    private String direction;
    /** DAILY | STRATEGIC (SYSTEM — yaratib bo'lmaydi) */
    private String scope;
    private String name;
    private Boolean active;
    private Integer sortOrder;
}
