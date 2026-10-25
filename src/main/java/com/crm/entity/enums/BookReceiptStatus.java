package com.crm.entity.enums;

/** Kitob kirimi holati (V87): tannarx kutilmoqda → to'lanmagan → qisman → to'langan. */
public enum BookReceiptStatus {
    /** Admin sonini kiritdi, tannarxni Direktor hali to'ldirmagan. */
    COST_PENDING,
    UNPAID,
    PARTIAL,
    PAID
}
