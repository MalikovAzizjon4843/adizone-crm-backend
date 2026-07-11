package com.crm.entity.enums;

/**
 * Payment record status (PAID/PENDING/…) and student billing status
 * (TRIAL/SUSPENDED/ARCHIVED also used on Student / StudentGroup).
 */
public enum PaymentStatus {
    PAID,
    PENDING,
    OVERDUE,
    PARTIAL,
    CANCELLED,
    TRIAL,
    SUSPENDED,
    ARCHIVED
}
